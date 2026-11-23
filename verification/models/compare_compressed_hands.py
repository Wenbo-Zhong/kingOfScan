from pathlib import Path
import argparse
import gc
import json
import cv2
import numpy as np
import onnxruntime as ort

parser = argparse.ArgumentParser(description="Compare actual four-rotation selections before and after weight compression")
parser.add_argument("--research-root", type=Path, required=True)
parser.add_argument("--candidates", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
root = args.output
root.mkdir(parents=True, exist_ok=True)
research = args.research_root
options = ort.SessionOptions()
options.intra_op_num_threads = 4
options.inter_op_num_threads = 1
cases = [
    ("partial fingers", research / "partial-fingers-input.jpg"),
    ("full hands", research / "egohos-reference/testimages/images/thu_draw_paper_RGB_draw_paper_lzy_2_38.jpg"),
    ("document without hands", research / "Handwriting-DeepLab/images/11.jpg"),
]
images = []
for index, (name, path) in enumerate(cases):
    bgr = cv2.imread(str(path))
    assert bgr is not None
    if index == 2:
        bgr = bgr[:, :bgr.shape[1] // 2]
    images.append(bgr)

def input_image(bgr):
    h, w = bgr.shape[:2]
    scale = 512 / max(w, h)
    nw, nh = int(w * scale + .5), int(h * scale + .5)
    rgb = cv2.cvtColor(cv2.resize(bgr, (nw, nh)), cv2.COLOR_BGR2RGB).astype(np.float32) - 128
    padded = np.zeros((512, 512, 3), dtype=np.float32)
    padded[:nh, :nw] = rgb
    return padded, nw, nh

outputs = {}
for tag, path in [("original", research / "converted/fingers.onnx"), ("w8", args.candidates / "fingers-w8.onnx")]:
    session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
    outputs[tag] = []
    for index, bgr in enumerate(images):
        padded, nw, nh = input_image(bgr)
        confidence = np.zeros((512, 512), dtype=np.float32)
        for turn in range(4):
            rotated = np.rot90(padded, -turn)
            tensor = np.ascontiguousarray(rotated.transpose(2, 0, 1)[None])
            value = session.run(["output"], {"image": tensor})[0][0, 0]
            confidence = np.maximum(confidence, np.rot90(value, turn))
        confidence = cv2.resize(confidence[:nh, :nw], (bgr.shape[1], bgr.shape[0]))
        radius = max(1, int(18 * max(bgr.shape[:2]) / 512 + .5))
        mask = cv2.dilate((confidence >= .2).astype(np.uint8), np.ones((2 * radius + 1,) * 2, np.uint8)).astype(bool)
        outputs[tag].append(mask)
        np.save(root / f"final-fingers-{tag}-{index}-mask.npy", mask)
        print(tag, cases[index][0], "selected", float(mask.mean()), flush=True)
    del session
    gc.collect()

report, rows = [], []
for index, bgr in enumerate(images):
    a, b = outputs["original"][index], outputs["w8"][index]
    intersection, union = np.count_nonzero(a & b), np.count_nonzero(a | b)
    entry = {
        "case": cases[index][0], "four_rotations": True, "threshold": .2,
        "iou_vs_original_selection": float(intersection / union) if union else 1.,
        "changed_fraction": float(np.mean(a != b)),
        "reference_fraction": float(a.mean()), "candidate_fraction": float(b.mean()),
    }
    report.append(entry)
    panels = [bgr]
    for mask in (a, b):
        overlay = bgr.copy()
        overlay[mask] = (overlay[mask] * .65 + np.array([0, 220, 0]) * .35).astype(np.uint8)
        panels.append(overlay)
    diff = bgr.copy()
    diff[a & ~b] = (0, 0, 255)
    diff[b & ~a] = (255, 0, 0)
    panels.append(diff)
    row = np.concatenate([cv2.resize(x, (400, 280)) for x in panels], axis=1)
    cv2.putText(row, cases[index][0] + " | input / original / INT8 / selection changes", (10, 24),
                cv2.FONT_HERSHEY_SIMPLEX, .55, (0, 0, 255), 2)
    rows.append(row)
    print(json.dumps(entry), flush=True)
cv2.imwrite(str(root / "final-fingers-comparison.png"), np.concatenate(rows, axis=0))
(root / "final-fingers-metrics.json").write_text(json.dumps(report, indent=2))
