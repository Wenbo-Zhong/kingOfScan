"""Install isolated real-image UI documents, preserving all other app documents."""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("fixtures", type=Path)
parser.add_argument("--ios-container", type=Path)
parser.add_argument("--android-serial")
args = parser.parse_args()
assert args.ios_container or args.android_serial
archive_buffer = io.BytesIO()
with tarfile.open(fileobj=archive_buffer, mode="w") as archive:
    for name in ["docres", "handwriting", "fingers", "watermark", "esdnet"]:
        image = Image.open(args.fixtures / f"{name}-page.png").convert("RGB")
        edge = {"docres": 256, "esdnet": 512}.get(name)
        if edge:
            left, top = max(0, (image.width-edge)//2), max(0, (image.height-edge)//2)
            image = image.crop((left, top, min(image.width, left+edge), min(image.height, top+edge)))
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        encoded = buffer.getvalue()
        doc_id = f"localaiverification{name}"
        content = {
            "pages/page.png": encoded,
            "pages/original-page.png": encoded,
            "meta.json": json.dumps({
                "id": doc_id, "title": f"AI-{name}", "createdAt": 1795400000000,
                "updatedAt": 1795400000000,
                "pages": [{"id": "page", "fileName": "page.png", "width": image.width,
                           "height": image.height, "filter": "Original",
                           "originalFileName": "original-page.png", "edit": {"filter": "Original"}}]
            }, ensure_ascii=False).encode(),
        }
        for relative, data in content.items():
            path = f"documents/{doc_id}/{relative}"
            entry = tarfile.TarInfo(path)
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
            if args.ios_container:
                destination = args.ios_container / "Documents/kingscan" / path
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
if args.android_serial:
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = [str(sdk / "platform-tools/adb"), "-s", args.android_serial]
    subprocess.run(adb + ["shell", "run-as", "com.kingscan.app", "mkdir", "-p", "files/kingscan"],
                   check=True, capture_output=True)
    subprocess.run(adb + ["exec-in", "run-as", "com.kingscan.app", "tar", "-C", "files/kingscan",
                         "-xf", "-"], input=archive_buffer.getvalue(), check=True, capture_output=True)
print("Installed five isolated AI UI documents")
