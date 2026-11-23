"""Validate actual author-presented stamped receipts with the trained residual diffusion."""
from pathlib import Path
import time
import json
import numpy as np
import cv2
import torch
from PIL import Image
import mobile_models as mm

torch.set_num_threads(4)
net, _ = mm.load("seal_init")
denoiser, _ = mm.load("seal_denoiser")
root = mm.ROOT
out = root / "converted"
image = cv2.cvtColor(cv2.imread(str(root / "seal-paper-input.png")), cv2.COLOR_BGR2RGB)
# Actual first receipt crop; preserve native character pixels, pad rather than resize.
image = image[:352, :352]
patch = image[48:304, 48:304]
Image.fromarray(patch).save(out / "seal-receipt-input.png")
condition = mm.tensor(patch.astype(np.float32) / 255)
gammas = torch.cumprod(1-torch.linspace(.00001, .2, 100), 0)
report = {}
torch.manual_seed(20261123)
noise = torch.randn_like(condition)
with torch.inference_mode():
    coarse = net(condition)
    outputs = {}
    for steps in [100, 20, 10, 5]:
        start = time.perf_counter()
        times = np.linspace(99, 0, steps).round().astype(np.int32).tolist()
        x = noise.clone()
        for rank, t in enumerate(times):
            ori = denoiser(torch.cat((x, coarse), 1), torch.tensor([float(t)]))
            if rank == len(times)-1:
                x = ori
            else:
                eps = (x-gammas[t].sqrt()*ori) / (1-gammas[t]).sqrt()
                previous = times[rank+1]
                x = gammas[previous].sqrt()*ori + (1-gammas[previous]).sqrt()*eps
        outputs[steps] = (x+coarse).numpy()
        result = (outputs[steps][0].transpose(1, 2, 0).clip(0, 1)*255).round().astype(np.uint8)
        Image.fromarray(result).save(out / f"seal-receipt-{steps}.png")
        delta = np.abs(outputs[steps]-outputs[100])
        report[str(steps)] = {"seconds": time.perf_counter()-start, "mae_vs_100": float(delta.mean()),
                              "max_vs_100": float(delta.max())}
        print(steps, report[str(steps)], flush=True)
    np.savez(out / "seal-receipt-reference.npz", image=condition.numpy(), noise=noise.numpy(),
             coarse=coarse.numpy(), **{f"steps_{k}": v for k, v in outputs.items()})
(out / "seal-receipt-validation.json").write_text(json.dumps(report, indent=2))
