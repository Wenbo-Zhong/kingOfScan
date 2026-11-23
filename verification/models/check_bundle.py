"""Check final built bundles against the versioned model manifest."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("ios_app", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
version = "kingscan-document-ai-20261123-v1"
results = {}
with zipfile.ZipFile(args.apk) as apk:
    manifest = json.loads(apk.read("assets/ai_models/models.json"))
    assert manifest["bundle_version"] == version
    hashes = {}
    for model in manifest["models"]:
        name = f'assets/ai_models/{model["id"]}.onnx'
        assert apk.getinfo(name).compress_type == zipfile.ZIP_STORED
        digest = hashlib.sha256()
        with apk.open(name) as file:
            while chunk := file.read(1024*1024):
                digest.update(chunk)
        assert digest.hexdigest() == model["onnx_sha256"], name
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
                          "uncompressed_mmap_models": True, "verification_entry_absent": True}
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
