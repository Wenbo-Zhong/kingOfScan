"""Create platform-specific native references from the verified compressed outputs."""
import argparse
import json
from pathlib import Path
import shutil
import numpy as np

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--original-fixtures", type=Path, required=True)
parser.add_argument("--candidates", type=Path, required=True)
parser.add_argument("--platform", choices=("android", "ios"), required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
for file in args.original_fixtures.iterdir():
    if file.is_file() and (file.suffix == ".png" or file.name.endswith("-input.f32") or file.name == "noise-reference.f32"):
        shutil.copy2(file, args.output / file.name)
for name, edge in dict(docres=256, esdnet=512, handwriting=512, fingers=512, watermark=256).items():
    path = args.candidates / f"{name}-{args.platform}-w8-{name}-{edge}-reference.npz"
    with np.load(path) as archive:
        original_input = np.fromfile(args.output / f"{name}-input.f32", dtype="<f4")
        assert np.array_equal(original_input, archive["image"].reshape(-1))
        archive["output"].astype("<f4").tofile(args.output / f"{name}-reference.f32")
(args.output / "reference-provenance.json").write_text(json.dumps({
    "reference": "compressed model host output; approximation against original models validated separately",
    "platform": args.platform, "candidates": str(args.candidates),
}, indent=2))
print(args.output)
