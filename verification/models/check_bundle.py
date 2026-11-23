"""Check final built bundles against the versioned model manifest."""
import argparse
import hashlib
import gzip
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("ios_app", type=Path, help="Built iOS app, or '-' to check Android alone")
parser.add_argument("output", type=Path)
args = parser.parse_args()
version = "kingscan-document-ai-20261123-v1"
results = {}
with zipfile.ZipFile(args.apk) as apk:
    manifest = json.loads(apk.read("assets/ai_models/models.json"))
    assert manifest["bundle_version"] == version
    hashes = {}
    model_bytes = 0
    packed_bytes = 0
    for model in manifest["models"]:
        name = f'assets/ai_models/{model.get("android_asset", model["id"] + ".onnx")}'
        compressed = model.get("android_asset_compression") == "gzip"
        assert apk.getinfo(name).compress_type == zipfile.ZIP_STORED
        if compressed:
            assert apk.getinfo(name).file_size == model["android_asset_bytes"]
            assert f'assets/ai_models/{model["id"]}.onnx' not in apk.namelist()
        digest = hashlib.sha256()
        count = 0
        with apk.open(name) as file:
            stream = gzip.GzipFile(fileobj=file) if compressed else file
            while chunk := stream.read(1024*1024):
                digest.update(chunk)
                count += len(chunk)
        assert digest.hexdigest() == model["onnx_sha256"], name
        assert count == model.get("onnx_bytes", count)
        model_bytes += count
        packed_bytes += apk.getinfo(name).compress_size
        hashes[model["id"]] = digest.hexdigest()
    assert len(hashes) == 5
    assert b"RefineNet for non-commercial purposes" in apk.read("assets/ai_models/licenses/RefineNet-LICENSE.txt")
    assert "non-commercial" in manifest["models"][0]["license"]
    excluded_entries = [b"DocumentAiVerificationKt", b"DocumentAiUiIntegrationTest", b"KMP_DOCUMENT_AI_TRACE"]
    for name in apk.namelist():
        if name.startswith("classes") and name.endswith(".dex"):
            dex = apk.read(name)
            assert all(entry not in dex for entry in excluded_entries), "Verification or tracing leaked into production APK"
    results["android"] = {"apk_bytes": args.apk.stat().st_size, "model_sha256": hashes,
                          "model_bytes": model_bytes, "packed_model_bytes": packed_bytes,
                          "gzip_weights_with_verified_cache_mmap": bool(manifest.get("packing_revision")),
                          "native_abis": sorted({n.split("/")[1] for n in apk.namelist() if n.startswith("lib/")}),
                          "native_library_bytes": sum(e.file_size for e in apk.infolist() if e.filename.startswith("lib/")),
                          "native_libraries_compressed": all(e.compress_type == zipfile.ZIP_DEFLATED
                                                            for e in apk.infolist() if e.filename.startswith("lib/")),
                          "verification_entry_absent": True}
    results["android"]["compressed_payload_bytes"] = {
        "models": packed_bytes,
        "native_libraries": sum(e.compress_size for e in apk.infolist() if e.filename.startswith("lib/")),
        "dex": sum(e.compress_size for e in apk.infolist() if e.filename.startswith("classes") and e.filename.endswith(".dex")),
        "other": sum(e.compress_size for e in apk.infolist()
                     if not (e.filename.startswith("lib/") or e.filename.endswith(".onnx.pack") or
                             (e.filename.startswith("classes") and e.filename.endswith(".dex")))),
    }
if str(args.ios_app) == "-":
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, indent=2))
    print(json.dumps(results, indent=2))
    raise SystemExit(0)
ios_manifest = args.ios_app / "models.json"
if not ios_manifest.is_file():
    ios_manifest = args.ios_app / "AIModels/models.json"
assert json.loads(ios_manifest.read_text()) == manifest
compiled = {}
for model in manifest["models"]:
    matches = list(args.ios_app.rglob(f'{model["id"]}.mlmodelc'))
    assert len(matches) == 1, matches
    assert any(p.is_file() for p in matches[0].rglob("*"))
    compiled[model["id"]] = str(matches[0].relative_to(args.ios_app))
ios_binary = (args.ios_app / "KingScan").read_bytes()
assert b"runIosDocumentAiVerification" not in ios_binary
assert b"KMP_DOCUMENT_AI_TRACE" not in ios_binary
results["ios"] = {"compiled_models": compiled, "manifest_matches_android": True,
                  "verification_entry_absent": True}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(results, indent=2))
print(json.dumps(results, indent=2))
