"""Compare bounded-memory tiles with full reference inference on original photographs."""
import json
import time
import cv2
import numpy as np
import torch
from PIL import Image
import mobile_models as mm

torch.set_num_threads(4)
root = mm.ROOT
out = root / "converted"
report = {}

def positions(size, edge, stride):
    if size <= edge:
        return [0]
    result = list(range(0, size-edge+1, stride))
    if result[-1] != size-edge:
        result.append(size-edge)
    return result

for kind, edge, size in [("docres", 256, 512), ("esdnet", 512, 1024)]:
    net, _ = mm.load(kind)
    if kind == "docres":
        bgr = cv2.imread(str(root / "DocRes/input/151_in.png"))[900:900+size, 1800:1800+size]
        data = np.concatenate((bgr, mm.docres_prompt(bgr)), 2).astype(np.float32) / 255
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    else:
        bgr = cv2.imread(str(root / "screen-001.jpg"))[900:900+size, 1300:1300+size]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        data = rgb.astype(np.float32)/255
    Image.fromarray(rgb).save(out / f"{kind}-tile-input.png")
    start = time.perf_counter()
    with torch.inference_mode():
        reference = net(mm.tensor(data)).numpy()[0].transpose(1, 2, 0)
    full_seconds = time.perf_counter()-start
    sums = np.zeros((size, size, 3), dtype=np.float32)
    weights = np.zeros((size, size), dtype=np.float32)
    coordinates = positions(size, edge, edge*3//4)
    axis = np.minimum(np.arange(edge)+1, edge-np.arange(edge)).astype(np.float32)
    axis = np.minimum(axis / (edge//4), 1)
    blend = axis[:, None]*axis[None, :]
    start = time.perf_counter()
    with torch.inference_mode():
        for top in coordinates:
            for left in coordinates:
                tile = net(mm.tensor(data[top:top+edge, left:left+edge])).numpy()[0].transpose(1, 2, 0)
                sums[top:top+edge, left:left+edge] += tile * blend[..., None]
                weights[top:top+edge, left:left+edge] += blend
    restored = sums / weights[..., None]
    delta = np.abs(restored-reference)
    report[kind] = {"full_seconds": full_seconds, "tile_seconds": time.perf_counter()-start,
                    "tiles": len(coordinates)**2, "mae_vs_full": float(delta.mean()),
                    "p99_vs_full": float(np.quantile(delta, .99)), "max_vs_full": float(delta.max())}
    print(kind, report[kind], flush=True)
    for name, result in [("full", reference), ("tiled", restored)]:
        if kind == "docres":
            result = result[..., ::-1]
        Image.fromarray((result.clip(0, 1)*255).round().astype(np.uint8)).save(out / f"{kind}-tile-{name}.png")
(out / "tile-validation.json").write_text(json.dumps(report, indent=2))
