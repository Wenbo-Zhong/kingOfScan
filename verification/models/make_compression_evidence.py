"""Render original and compressed tensor outputs from the same real-image inputs."""
import argparse
import json
from pathlib import Path
import shutil
import cv2
import numpy as np

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--research-root", type=Path, required=True)
parser.add_argument("--candidates", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
report = {}
for name, edge in dict(docres=256, esdnet=512, handwriting=512, fingers=512, watermark=256).items():
    original = cv2.imread(str(args.research_root / f"converted/{name}-{edge}-input.png"))
    assert original is not None
    panels = [original]
    for platform in ("android", "ios"):
        archive_path = args.candidates / f"{name}-{platform}-w8-{name}-{edge}-reference.npz"
        with np.load(archive_path) as data:
            reference, output = data["reference"], data["output"]
        if platform == "android":
            values = (reference, output)
        else:
            values = (output,)
        for value in values:
            if value.shape[1] == 3:
                image = (value[0].transpose(1, 2, 0).clip(0, 1) * 255).round().astype(np.uint8)
                if name != "docres":
                    image = image[..., ::-1]
            else:
                confidence = value[0, 0].copy()
                if name == "handwriting":
                    confidence -= confidence.min()
                    confidence /= max(float(confidence.max()), 1e-6)
                mask = confidence >= (.2 if name == "fingers" else .35)
                image = original.copy()
                image[mask] = (image[mask] * .65 + np.array([0, 220, 0]) * .35).astype(np.uint8)
            panels.append(image)
        metrics = json.loads((args.candidates / f"{name}-{platform}-w8-metrics.json").read_text())
        report.setdefault(name, {})[platform] = metrics
    scaled = [cv2.resize(image, (320, 320)) for image in panels]
    row = np.concatenate(scaled, axis=1)
    header = np.full((45, row.shape[1], 3), 255, dtype=np.uint8)
    cv2.putText(header, name + " | input / original model / Android INT8 / iOS INT8", (10, 30),
                cv2.FONT_HERSHEY_SIMPLEX, .6, (50, 50, 50), 1)
    cv2.imwrite(str(args.output / f"{name}-outputs.png"), np.concatenate((header, row), axis=0))
(args.output / "quantization-metrics.json").write_text(json.dumps(report, indent=2))
print(args.output)
