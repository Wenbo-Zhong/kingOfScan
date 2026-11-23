"""Compress the existing inference graphs; keep float activations and model interfaces.

Input: a verified research directory containing converted/{id}.onnx/.mlpackage
and converted/{id}-{edge}-reference.npz. Outputs never overwrite the reference.
"""
import argparse
import gc
import json
from pathlib import Path
import hashlib
import numpy as np

EDGES = dict(fingers=512, handwriting=512, docres=256, esdnet=512, watermark=256)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def compress_onnx(source, target):
    import onnx
    from onnx import helper, numpy_helper, TensorProto
    model = onnx.load(str(source))
    weights = {node.input[1] for node in model.graph.node
               if node.op_type in ("Conv", "ConvTranspose", "MatMul", "Gemm") and len(node.input) > 1}
    kept, extra, nodes = [], [], []
    for tensor in model.graph.initializer:
        if tensor.name not in weights or tensor.data_type != TensorProto.FLOAT:
            kept.append(tensor)
            continue
        weight = numpy_helper.to_array(tensor)
        if weight.size < 2048 or weight.ndim < 2:
            kept.append(tensor)
            continue
        # Axis zero is the stored channel axis, including transposed convolutions.
        scale = np.maximum(np.max(np.abs(weight), axis=tuple(range(1, weight.ndim))) / 127,
                           np.finfo(np.float32).tiny).astype(np.float32)
        quantized = np.rint(weight / scale.reshape((-1,) + (1,) * (weight.ndim - 1)))
        quantized = quantized.clip(-127, 127).astype(np.int8)
        prefix = tensor.name + "__w8"
        extra.extend([
            numpy_helper.from_array(quantized, prefix + "_quantized"),
            numpy_helper.from_array(scale, prefix + "_scale"),
            numpy_helper.from_array(np.zeros(scale.size, dtype=np.int8), prefix + "_zero"),
        ])
        nodes.append(helper.make_node("DequantizeLinear",
            [prefix + "_quantized", prefix + "_scale", prefix + "_zero"], [tensor.name],
            name=prefix + "_dequantize", axis=0))
    original_nodes = list(model.graph.node)
    del model.graph.initializer[:]
    model.graph.initializer.extend(kept + extra)
    del model.graph.node[:]
    model.graph.node.extend(nodes + original_nodes)
    onnx.checker.check_model(model)
    onnx.save(model, str(target))


def metrics(reference, output, name):
    assert reference.shape == output.shape and np.isfinite(output).all()
    delta = np.abs(output - reference)
    result = {"mae": float(delta.mean()), "max": float(delta.max()),
              "p99": float(np.quantile(delta, .99))}
    if reference.shape[1] == 1:
        a, b = reference.copy(), output.copy()
        if name == "handwriting":
            for value in (a, b):
                value -= value.min()
                value /= max(float(value.max()), 1e-6)
        threshold = .2 if name == "fingers" else .35
        a, b = a >= threshold, b >= threshold
        result.update(threshold=threshold,
            iou=float(np.count_nonzero(a & b) / max(np.count_nonzero(a | b), 1)),
            changed_fraction=float(np.mean(a != b)))
    else:
        mse = float(np.mean((output.clip(0, 1) - reference.clip(0, 1)) ** 2))
        result["psnr_vs_original_model_db"] = float(-10 * np.log10(max(mse, 1e-20)))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--research-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--platform", choices=("android", "ios", "both"), default="both")
    parser.add_argument("--models", nargs="+", choices=list(EDGES), default=list(EDGES))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    source = args.research_root / "converted"
    for name in args.models:
        for platform in ("android", "ios"):
            if args.platform not in ("both", platform):
                continue
            if platform == "android":
                import onnxruntime as ort
                original = source / f"{name}.onnx"
                target = args.output / f"{name}-w8.onnx"
                compress_onnx(original, target)
                options = ort.SessionOptions()
                options.intra_op_num_threads = 4
                options.inter_op_num_threads = 1
                model = ort.InferenceSession(str(target), options, providers=["CPUExecutionProvider"])
                predict = lambda image: model.run(["output"], {"image": image})[0]
                metadata = {"onnxruntime": ort.__version__, "original_sha256": sha256(original),
                            "output_sha256": sha256(target)}
            else:
                import coremltools as ct
                from coremltools.optimize.coreml import (
                    OpLinearQuantizerConfig, OptimizationConfig, linear_quantize_weights,
                )
                original = source / f"{name}.mlpackage"
                target = args.output / f"{name}-w8.mlpackage"
                model = ct.models.MLModel(str(original), skip_model_load=True)
                config = OptimizationConfig(global_config=OpLinearQuantizerConfig(
                    mode="linear_symmetric", dtype="int8", granularity="per_channel", weight_threshold=2048))
                compressed = linear_quantize_weights(model, config)
                compressed.save(str(target))
                metadata = {"coremltools": ct.__version__,
                            "specification_version": compressed.get_spec().specificationVersion}
                del model, compressed
                gc.collect()
                model = ct.models.MLModel(str(target), compute_units=ct.ComputeUnit.CPU_AND_GPU)
                predict = lambda image: np.asarray(model.predict({"image": image})["output"])
            size = lambda path: (path.stat().st_size if path.is_file()
                                else sum(p.stat().st_size for p in path.rglob("*") if p.is_file()))
            metadata.update(id=name, platform=platform, dtype="int8", weight_only=True,
                            input_bytes=size(original), output_bytes=size(target), cases=[])
            files = [f"{name}-{EDGES[name]}-reference.npz"]
            if name == "fingers":
                files.append("fingers-full-reference.npz")
            for filename in files:
                with np.load(source / filename) as data:
                    image, reference = data["image"], data["reference"]
                output = predict(image)
                result = dict(input=filename, **metrics(reference, output, name))
                metadata["cases"].append(result)
                np.savez_compressed(args.output / f"{name}-{platform}-w8-{Path(filename).stem}.npz",
                                    image=image, reference=reference, output=output)
                print(name, platform, result, flush=True)
            (args.output / f"{name}-{platform}-w8-metrics.json").write_text(json.dumps(metadata, indent=2))
            del model, predict
            gc.collect()


if __name__ == "__main__":
    main()
