"""Stage verified INT8 models, deterministic gzip assets, and their provenance."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            result.update(chunk)
    return result.hexdigest()


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--candidates", type=Path, required=True)
parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
args = parser.parse_args()
android = args.repo_root / "composeApp/src/androidMain/assets/ai_models"
ios = args.repo_root / "iosApp/iosApp/AIModels"
manifest = json.loads((android / "models.json").read_text())
manifest["packing_revision"] = "kingscan-document-ai-20261123-w8-gzip"
manifest["precision"] = "INT8 weights; float activations; existing v1 edit and preprocessing contracts"
for entry in manifest["models"]:
    name = entry["id"]
    onnx = args.candidates / f"{name}-w8.onnx"
    package = args.candidates / f"{name}-w8.mlpackage"
    android_metrics = json.loads((args.candidates / f"{name}-android-w8-metrics.json").read_text())
    ios_metrics = json.loads((args.candidates / f"{name}-ios-w8-metrics.json").read_text())
    assert android_metrics["output_sha256"] == digest(onnx)
    assert ios_metrics["specification_version"] == 7  # iOS 16, no deployment-target increase.
    assert android_metrics["cases"] and ios_metrics["cases"]
    # Numerical changes are approximation measurements, not ground-truth quality scores.
    for metrics in (android_metrics, ios_metrics):
        for case in metrics["cases"]:
            if name == "fingers":
                assert case["iou"] >= .94, case
            elif name == "handwriting":
                assert case["iou"] >= .99, case
            else:
                assert case["psnr_vs_original_model_db"] >= 40, case
    if "baseline_validation" not in entry:
        entry["baseline_validation"] = entry.pop("validation")
        entry["baseline_onnx_sha256"] = entry["onnx_sha256"]
        entry["baseline_coreml_files_sha256"] = entry["coreml_files_sha256"]
    assert android_metrics["original_sha256"] == entry["baseline_onnx_sha256"]
    # AGP automatically expands .gz sources and strips the suffix while merging.
    asset = android / f"{name}.onnx.pack"
    with onnx.open("rb") as source, asset.open("wb") as destination:
        with gzip.GzipFile(filename="", fileobj=destination, mode="wb", compresslevel=6, mtime=0) as compressed:
            shutil.copyfileobj(source, compressed, length=1024 * 1024)
    with gzip.open(asset, "rb") as stream:
        assert hashlib.file_digest(stream, "sha256").hexdigest() == digest(onnx)
    (android / f"{name}.onnx").unlink(missing_ok=True)
    (android / f"{name}.onnx.gz").unlink(missing_ok=True)
    target = ios / f"{name}.mlpackage"
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(package, target)
    entry.update(
        onnx_sha256=digest(onnx), onnx_bytes=onnx.stat().st_size,
        android_asset=f"{name}.onnx.pack", android_asset_compression="gzip",
        android_asset_bytes=asset.stat().st_size, android_asset_sha256=digest(asset),
        coreml_files_sha256={str(p.relative_to(target)): digest(p) for p in target.rglob("*") if p.is_file()},
        coreml_bytes=sum(p.stat().st_size for p in target.rglob("*") if p.is_file()),
        validation_environment="macOS ORT CPU and Core ML CPU/GPU; device results recorded separately",
        validation={"android_weight_quantization": android_metrics, "ios_weight_quantization": ios_metrics},
    )
for directory in (android, ios):
    (directory / "models.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(json.dumps({entry["id"]: {"android_gzip_bytes": entry["android_asset_bytes"],
                              "android_model_bytes": entry["onnx_bytes"],
                              "coreml_package_bytes": entry["coreml_bytes"]}
                  for entry in manifest["models"]}, indent=2))
