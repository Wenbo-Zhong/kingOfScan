"""Validate files saved through the three completed Device Hub UI flows."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("container", type=Path)
parser.add_argument("fixtures", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
root = args.container / "Documents/kingscan/documents"
report = []
for name, kind in [("docres", "SmartHd"), ("handwriting", "Handwriting"), ("fingers", "Fingers")]:
    directory = root / f"localaiverification{name}"
    metadata = json.loads((directory / "meta.json").read_text())
    page = metadata["pages"][0]
    effects = page["edit"]["retouches"]
    assert len(effects) == 1 and effects[0]["kind"] == kind
    expected = Image.open(args.fixtures / f"{name}-page.png").convert("RGB")
    if name == "docres":
        left, top = max(0, (expected.width-256)//2), max(0, (expected.height-256)//2)
        expected = expected.crop((left, top, min(expected.width, left+256), min(expected.height, top+256)))
    buffer = io.BytesIO()
    expected.save(buffer, format="PNG")
    original = (directory / "pages" / page["originalFileName"]).read_bytes()
    assert original == buffer.getvalue(), f"{name}: original bytes changed"
    result = Image.open(directory / "pages" / page["fileName"])
    assert result.size == expected.size == (page["width"], page["height"])
    report.append(f"PASS {name} UI-saved model kind, dimensions and original SHA256={hashlib.sha256(original).hexdigest()}")
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text("\n".join(report))
print("\n".join(report))
