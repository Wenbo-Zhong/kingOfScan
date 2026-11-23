"""Faithful inference-only adapters. Upstream licenses remain in each source checkout."""
from pathlib import Path
import importlib.util
import sys
import torch
import numpy as np
import cv2

ROOT = Path(__file__).parent


def module(name, path, source=None):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    if source is None:
        spec.loader.exec_module(mod)
    else:
        exec(compile(source, str(path), "exec"), mod.__dict__)
    return mod


class FirstOutput(torch.nn.Module):
    def __init__(self, net, nested=False):
        super().__init__()
        self.net = net
        self.nested = nested

    def forward(self, image):
        result = self.net(image)
        return result[0][0] if self.nested else result[0]


class InitialOutput(torch.nn.Module):
    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, image):
        return self.net(image, None)


class WatermarkOutput(torch.nn.Module):
    """Five deterministic DDIM steps using the original 100-step trained alpha schedule."""
    def __init__(self, initial, denoiser):
        super().__init__()
        self.initial = initial
        self.denoiser = denoiser
        self.register_buffer("gammas", torch.cumprod(1-torch.linspace(.00001, .2, 100), 0))

    def forward(self, image):
        coarse = self.initial(image[:, :3])
        x = image[:, 3:]
        times = [99, 74, 50, 25, 0]
        for rank, time in enumerate(times):
            t = torch.full((1,), float(time), dtype=torch.float32, device=image.device)
            ori = self.denoiser(torch.cat((x, coarse), 1), t)
            if rank == len(times)-1:
                x = ori
            else:
                eps = (x-self.gammas[time].sqrt()*ori) / (1-self.gammas[time]).sqrt()
                previous = times[rank+1]
                x = self.gammas[previous].sqrt()*ori + (1-self.gammas[previous]).sqrt()*eps
        return coarse+x


def load(kind):
    if kind == "fingers":
        from hand_refinenet import Hands
        return Hands().eval(), 3
    if kind == "watermark":
        initial, _ = load("seal_init")
        denoiser, _ = load("seal_denoiser")
        return WatermarkOutput(initial, denoiser).eval(), 6
    if kind == "docres":
        path = ROOT / "DocRes/models/restormer_arch.py"
        source = path.read_text()
        # Exact index-preserving replacements avoid einops' traced shape-to-int nodes.
        source = source.replace("rearrange(x, 'b c h w -> b (h w) c')", "x.flatten(2).transpose(1, 2)")
        source = source.replace("rearrange(x, 'b (h w) c -> b c h w',h=h,w=w)", "x.transpose(1, 2).reshape(x.shape[0], x.shape[2], h, w)")
        for name in ("q", "k", "v"):
            source = source.replace(f"rearrange({name}, 'b (head c) h w -> b head c (h w)', head=self.num_heads)",
                                    f"{name}.reshape(b, self.num_heads, c // self.num_heads, h * w)")
        source = source.replace("rearrange(out, 'b head c (h w) -> b (head c) h w', head=self.num_heads, h=h, w=w)",
                                "out.reshape(b, c, h, w)")
        mod = module("king_docres", path, source)
        net = mod.Restormer(inp_channels=6, out_channels=3, dim=48,
                            num_blocks=[2, 3, 3, 4], num_refinement_blocks=4,
                            heads=[1, 2, 4, 8], ffn_expansion_factor=2.66,
                            bias=False, LayerNorm_type="WithBias", dual_pixel_task=True)
        state = torch.load(ROOT / "weights/docres.pkl", map_location="cpu", weights_only=True)["model_state"]
        net.load_state_dict({k.removeprefix("module."): v for k, v in state.items()}, strict=True)
        return net.eval(), 6
    if kind == "esdnet":
        mod = module("king_esdnet", ROOT / "UHDM/model/nets.py")
        net = mod.my_model(en_feature_num=48, en_inter_num=32, de_feature_num=64,
                           de_inter_num=32, sam_number=1)
        net.load_state_dict(torch.load(ROOT / "weights/esdnet-mix.pth", map_location="cpu", weights_only=True), strict=True)
        return FirstOutput(net).eval(), 3
    if kind == "handwriting":
        mod = module("king_dis", ROOT / "DIS/IS-Net/models/isnet.py")
        net = mod.ISNetDIS()
        net.load_state_dict(torch.load(ROOT / "weights/handwriting-isnet.pth", map_location="cpu", weights_only=True), strict=True)
        return FirstOutput(net, nested=True).eval(), 3
    if kind in ("seal_init", "seal_denoiser"):
        # Only remove the CUDA default argument. Every inference supplies the actual timestep.
        sys.path.insert(0, str(ROOT / "DocDiff"))
        path = ROOT / "DocDiff/model/DocDiff.py"
        source = path.read_text().replace(
            "t: torch.Tensor=torch.tensor([0]).cuda()", "t: Optional[torch.Tensor]=None")
        mod = module("king_docdiff", path, source)
        denoiser = kind == "seal_denoiser"
        net = mod.UNet(input_channels=6 if denoiser else 3, output_channels=3,
                       n_channels=32, ch_mults=[1, 2, 3, 4], n_blocks=1, is_noise=denoiser)
        net.load_state_dict(torch.load(ROOT / f"DocDiff/checksave/{kind}.pth",
                                       map_location="cpu", weights_only=True), strict=True)
        return (net if denoiser else InitialOutput(net)).eval(), 6 if denoiser else 3
    raise ValueError(kind)


def docres_prompt(bgr):
    x = cv2.convertScaleAbs(cv2.Sobel(bgr, cv2.CV_16S, 1, 0))
    y = cv2.convertScaleAbs(cv2.Sobel(bgr, cv2.CV_16S, 0, 1))
    gray = cv2.cvtColor(cv2.addWeighted(x, .5, y, .5, 0), cv2.COLOR_BGR2GRAY)
    return np.repeat(gray[..., None], 3, axis=2)


def tensor(array):
    return torch.from_numpy(np.ascontiguousarray(array.transpose(2, 0, 1))).unsqueeze(0).float()


def sample(kind, edge):
    if kind == "fingers":
        archive = np.load(ROOT / "converted/fingers-partial-reference.npz")
        rgb = (archive["image"][0].transpose(1, 2, 0)+128).clip(0,255).astype(np.uint8)
        return torch.from_numpy(archive["image"]), rgb
    if kind == "watermark":
        assert edge == 256
        archive = np.load(ROOT / "converted/seal-receipt-reference.npz")
        return torch.from_numpy(np.concatenate((archive["image"], archive["noise"]), 1)), (
            archive["image"][0].transpose(1, 2, 0)*255).round().astype(np.uint8)
    if kind == "esdnet":
        # Actual author-provided photographed-screen example; center crop retains native moire.
        bgr = cv2.imread(str(ROOT / "screen-001.jpg"))
        h, w = bgr.shape[:2]
        y, x = (h - edge) // 2, (w - edge) // 2
        rgb = cv2.cvtColor(bgr[y:y+edge, x:x+edge], cv2.COLOR_BGR2RGB)
        return tensor(rgb.astype(np.float32) / 255), rgb
    if kind == "docres":
        bgr = cv2.imread(str(ROOT / "DocRes/input/for_debluring.png"))
        h, w = bgr.shape[:2]
        y, x = max(0, (h-edge)//2), max(0, (w-edge)//2)
        bgr = bgr[y:y+edge, x:x+edge]
        bgr = cv2.copyMakeBorder(bgr, 0, edge-bgr.shape[0], 0, edge-bgr.shape[1], cv2.BORDER_REFLECT_101)
        return tensor(np.concatenate([bgr, docres_prompt(bgr)], 2).astype(np.float32) / 255), cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    if kind == "handwriting":
        bgr = cv2.imread(str(ROOT / "Handwriting-DeepLab/images/11.jpg"))
        # Repository image is a before/after comparison. Only the original left panel is input.
        bgr = bgr[:, :bgr.shape[1] // 2]
        h, w = bgr.shape[:2]
        ratio = min(1., edge / max(h, w))
        bgr = cv2.resize(bgr, (int(w*ratio), int(h*ratio)), interpolation=cv2.INTER_AREA)
        padded = np.ones((edge, edge, 3), dtype=np.float32)
        padded[:bgr.shape[0], :bgr.shape[1]] = bgr
        return tensor(padded / 255 - .5), cv2.cvtColor(padded.astype(np.uint8), cv2.COLOR_BGR2RGB)
    rgb = cv2.cvtColor(cv2.imread(str(ROOT / "DocDiff/demo/0000184_blur.png")), cv2.COLOR_BGR2RGB)
    h, w = rgb.shape[:2]
    y, x = max(0, (h-edge)//2), max(0, (w-edge)//2)
    rgb = rgb[y:y+edge, x:x+edge]
    rgb = cv2.copyMakeBorder(rgb, 0, edge-rgb.shape[0], 0, edge-rgb.shape[1], cv2.BORDER_REFLECT_101)
    image = tensor(rgb.astype(np.float32) / 255)
    if kind == "seal_denoiser":
        net, _ = load("seal_init")
        with torch.inference_mode():
            condition = net(image)
        # Residual from the real degraded document, for deterministic conversion verification.
        return (torch.cat((image-condition, condition), dim=1), torch.tensor([50.], dtype=torch.float32)), rgb
    return image, rgb
