"""Stage only numerically verified model assets, with upstream licenses and exact provenance."""
from pathlib import Path
import hashlib
import json
import shutil
import argparse
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--research-root", type=Path, default=Path(__file__).parent)
parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
parser.add_argument("--metadata-only", action="store_true", help="Refresh notices without copying model weights")
args = parser.parse_args()
root = args.research_root
repo = args.repo_root
android = repo / "composeApp/src/androidMain/assets/ai_models"
ios = repo / "iosApp/iosApp/AIModels"
android.mkdir(parents=True, exist_ok=True)
ios.mkdir(parents=True, exist_ok=True)
models = [
    ("fingers", 512, 3, 1, "https://github.com/aurooj/Hand-Segmentation-in-the-Wild",
     "HandsWild code: MIT; RefineNet: non-commercial; checkpoint: no separate license declaration",
     "HandsWild/license.txt", ["weights/hand-refinenet-eyth.mat"]),
    ("docres", 256, 6, 3, "https://github.com/ZZZHANG-jx/DocRes", "MIT", "DocRes/LICENSE",
     ["weights/docres.pkl"]),
    ("esdnet", 512, 3, 3, "https://github.com/CVMI-Lab/UHDM", "Apache-2.0", "UHDM/LICENSE.md",
     ["weights/esdnet-mix.pth"]),
    ("handwriting", 512, 3, 1, "https://huggingface.co/Inoob/DIS-Handwriting-Remover", "Apache-2.0",
     "DIS/LICENSE.md", ["weights/handwriting-isnet.pth"]),
    ("watermark", 256, 6, 3, "https://github.com/Royalvice/DocDiff", "MIT", "DocDiff/LICENSE",
     ["DocDiff/checksave/seal_init.pth", "DocDiff/checksave/seal_denoiser.pth"]),
]
manifest = {"bundle_version": "kingscan-document-ai-20261123-v1", "models": []}
checkpoint_sources = {
    "fingers": "https://drive.google.com/file/d/12HRYXdHWOGkl71QqUdlijCq2w2ARa6-M/view",
    "docres": "https://huggingface.co/spaces/qubvel-hf/documents-restoration/resolve/main/checkpoints/docres.pkl",
    "esdnet": "https://huggingface.co/spaces/ECCV2022/Screen_Image_Demoireing/resolve/main/mix.pth",
    "handwriting": "https://huggingface.co/Inoob/DIS-Handwriting-Remover",
    "watermark": "https://github.com/Royalvice/DocDiff/tree/main/checksave",
}
code_directories = {"fingers": "HandsWild", "docres": "DocRes", "esdnet": "UHDM",
                    "handwriting": "DIS", "watermark": "DocDiff"}
for name, edge, cin, cout, source, license_name, license_path, checkpoints in models:
    validation = root / f"converted/{name}-{edge}-validation.json"
    entry = json.loads(validation.read_text())
    assert any(k.startswith("coreml") for k in entry["errors"])
    assert "onnx_cpu" in entry["errors"]
    onnx = root / f"converted/{name}.onnx"
    package = root / f"converted/{name}.mlpackage"
    assert onnx.is_file() and package.is_dir()
    if not args.metadata_only:
        shutil.copy2(onnx, android / onnx.name)
        shutil.copytree(package, ios / package.name, dirs_exist_ok=True)
    for destination in [android, ios]:
        (destination / "licenses").mkdir(exist_ok=True)
        shutil.copy2(root / license_path, destination / f"licenses/{name}-LICENSE.txt")
    manifest["models"].append({
        "id": name, "version": manifest["bundle_version"], "edge": edge,
        "input_shape": [1, cin, edge, edge], "output_shape": [1, cout, edge, edge],
        "source": source, "license": license_name,
        "checkpoint_source": checkpoint_sources[name],
        "code_revision": subprocess.run(
            ["git", "-C", str(root / code_directories[name]), "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True).stdout.strip(),
        "validation_environment": "macOS conversion reference; device acceptance recorded separately",
        "onnx_sha256": hashlib.sha256(onnx.read_bytes()).hexdigest(),
        "coreml_files_sha256": {str(p.relative_to(package)): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in package.rglob("*") if p.is_file()},
        "upstream_checkpoint_sha256": {str(p): hashlib.sha256((root/p).read_bytes()).hexdigest() for p in checkpoints},
        "validation": entry["errors"],
    })
for destination in [android, ios]:
    shutil.copy2(root / "RefineNet/LICENSE", destination / "licenses/RefineNet-LICENSE.txt")
    shutil.copy2(repo / "verification/models/THIRD_PARTY_NOTICES.txt", destination / "THIRD_PARTY_NOTICES.txt")
    (destination / "models.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
shutil.copy2(repo / "verification/models/onnxruntime-NOTICE.txt", android / "licenses/onnxruntime-NOTICE.txt")
print(json.dumps(manifest, ensure_ascii=False, indent=2))
