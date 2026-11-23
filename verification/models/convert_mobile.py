from pathlib import Path
import argparse
import time
import json
import hashlib
import numpy as np
import torch
from PIL import Image
import mobile_models as mm

parser = argparse.ArgumentParser()
parser.add_argument("kind")
parser.add_argument("--edge", type=int, default=256)
parser.add_argument("--format", choices=["both", "onnx", "coreml"], default="both")
args = parser.parse_args()
root = mm.ROOT
output = root / "converted"
output.mkdir(exist_ok=True)
torch.set_num_threads(4)
model, channels = mm.load(args.kind)
inputs, rgb = mm.sample(args.kind, args.edge)
inputs = inputs if isinstance(inputs, tuple) else (inputs,)
names = ["image", "timestep"] if len(inputs) == 2 else ["image"]
print("loaded", args.kind, "parameters", sum(p.numel() for p in model.parameters()), "input", [tuple(i.shape) for i in inputs], flush=True)
start = time.perf_counter()
with torch.inference_mode():
    reference = model(*inputs).numpy()
print("reference", reference.shape, "seconds", time.perf_counter()-start, flush=True)
stats = {"kind": args.kind, "edge": args.edge, "shape": list(reference.shape), "errors": {}}
np.savez(output / f"{args.kind}-{args.edge}-reference.npz", reference=reference, **{k: v.numpy() for k, v in zip(names, inputs)})
Image.fromarray(rgb).save(output / f"{args.kind}-{args.edge}-input.png")
if reference.shape[1] == 3:
    restored = (reference[0].transpose(1, 2, 0).clip(0, 1) * 255).round().astype(np.uint8)
    if args.kind == "docres":
        restored = restored[..., ::-1]
    Image.fromarray(restored).save(output / f"{args.kind}-{args.edge}-reference.png")
else:
    Image.fromarray((reference[0, 0].clip(0, 1)*255).round().astype(np.uint8)).save(output / f"{args.kind}-{args.edge}-mask.png")

def compare(name, result):
    delta = np.abs(reference-result)
    entry = {"mae": float(delta.mean()), "max": float(delta.max()), "p99": float(np.quantile(delta, .99))}
    stats["errors"][name] = entry
    print(name, entry, flush=True)
    assert np.isfinite(result).all()
    # Masks can flip thresholds near the boundary; preserve float values for later visual comparison.
    assert entry["mae"] < .003, entry

if args.format in ("both", "onnx"):
    import onnx
    import onnxruntime as ort
    target = output / f"{args.kind}.onnx"
    torch.onnx.export(model, inputs, target, input_names=names, output_names=["output"],
                      opset_version=17, do_constant_folding=True, dynamo=False)
    onnx.checker.check_model(str(target))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(target), options, providers=["CPUExecutionProvider"])
    result = session.run(["output"], {k: v.numpy() for k, v in zip(names, inputs)})[0]
    compare("onnx_cpu", result)
    stats["onnx_sha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
    del session
if args.format in ("both", "coreml"):
    import coremltools as ct
    traced = torch.jit.trace(model, inputs, strict=True, check_trace=False)
    target = output / f"{args.kind}.mlpackage"
    converted = ct.convert(traced, convert_to="mlprogram",
                           inputs=[ct.TensorType(name=n, shape=tuple(v.shape), dtype=np.float32) for n, v in zip(names, inputs)],
                           outputs=[ct.TensorType(name="output", dtype=np.float32)],
                           minimum_deployment_target=ct.target.iOS16,
                           compute_precision=ct.precision.FLOAT16,
                           compute_units=ct.ComputeUnit.CPU_AND_GPU)
    converted.author = "KingOfScan adapter; original authors and licenses in model manifest"
    converted.short_description = f"{args.kind}: offline document restoration; fixed {args.edge}px inference"
    converted.save(str(target))
    result = converted.predict({k: v.numpy() for k, v in zip(names, inputs)})["output"]
    compare("coreml_cpu_gpu_fp16", result)
    np.save(output / f"{args.kind}-{args.edge}-coreml.npy", result)
(output / f"{args.kind}-{args.edge}-validation.json").write_text(json.dumps(stats, indent=2))
print("verified", args.kind, flush=True)
