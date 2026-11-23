"""Compare actual native tensors with both compressed host and original-model outputs."""
import argparse
import json
from pathlib import Path
import numpy as np
from compress_mobile import metrics

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--candidates", type=Path, required=True)
parser.add_argument("--native-evidence", type=Path, required=True)
parser.add_argument("--platform", choices=("android", "ios"), required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
report = {"platform": args.platform, "models": {}}
for name, edge in dict(docres=256, esdnet=512, handwriting=512, fingers=512, watermark=256).items():
    with np.load(args.candidates / f"{name}-{args.platform}-w8-{name}-{edge}-reference.npz") as data:
        baseline, expected = data["reference"], data["output"]
    actual = np.fromfile(args.native_evidence / f"{name}-native.f32", dtype="<f4").reshape(expected.shape)
    compatibility = metrics(expected, actual, name)
    assert compatibility["mae"] < .003, (name, compatibility)
    report["models"][name] = {
        "native_vs_compressed_host": compatibility,
        "native_vs_original_model": metrics(baseline, actual, name),
    }
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
