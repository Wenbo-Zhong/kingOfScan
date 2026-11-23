"""Inference-only translation of the author's serialized EYTH DAG, not a replacement architecture."""
from pathlib import Path
import json, h5py, numpy as np, torch
import torch.nn.functional as F
ROOT = Path(__file__).parent

def seq(x):
    return x if isinstance(x, list) else [x]

def ints(x):
    return [int(i) for i in np.asarray(x).flatten()]

def bicubic(x, shape):
    # MATLAB imresize: Keys cubic a=-0.5, pixel-center mapping, symmetric extension.
    for axis, target in [(2, int(shape[0])), (3, int(shape[1]))]:
        source = int(x.shape[axis])
        if source == target:
            continue
        u = (np.arange(target)+.5)*source/target-.5
        left = np.floor(u).astype(int)-1
        indexes = left[:,None]+np.arange(4)[None,:]
        z = np.abs(u[:,None]-indexes)
        w = np.where(z<=1,1.5*z**3-2.5*z**2+1,
                     np.where(z<=2,-.5*z**3+2.5*z**2-4*z+2,0))
        indexes = indexes % (2*source)
        indexes = np.where(indexes>=source,2*source-1-indexes,indexes)
        result = 0
        for k in range(4):
            index = torch.tensor(indexes[:,k],dtype=torch.long,device=x.device)
            weightshape = [1]*4
            weightshape[axis] = target
            weights = torch.tensor(w[:,k],dtype=x.dtype,device=x.device).reshape(weightshape)
            result = result + x.index_select(axis,index)*weights
        x = result
    return x

class DAG(torch.nn.Module):
    def __init__(self, graph, h5):
        super().__init__()
        layers = graph["layers"]
        self.steps = []
        def value(v):
            return np.asarray(h5[v["ref"]][()],dtype=np.float32) if isinstance(v,dict) else np.asarray(v,dtype=np.float32)
        params = {k:value(v) for k,v in zip(graph["params"]["name"],graph["params"]["value"])}
        for i,typ in enumerate(layers["type"]):
            name, block = layers["name"][i],layers["block"][i]
            inputs,outputs = seq(layers["inputs"][i]),seq(layers["outputs"][i])
            p = seq(layers["params"][i])
            if typ == "dagnn.Conv":
                weights = params[p[0]].transpose(0,1,3,2).copy()
                s=ints(block["stride"]);pad=ints(block["pad"]);di=ints(block["dilate"])
                assert len(set(pad))==1
                op = torch.nn.Conv2d(weights.shape[1],weights.shape[0],weights.shape[2:],
                                     stride=tuple(s),padding=pad[0],dilation=tuple(di),bias=len(p)==2)
                op.weight.data.copy_(torch.from_numpy(weights))
                if len(p)==2: op.bias.data.copy_(torch.from_numpy(params[p[1]].flatten()))
            elif typ == "dagnn.BatchNorm":
                mult,bias,moments = [params[k] for k in p]
                a=mult.flatten()/moments[1].flatten()
                b=bias.flatten()-moments[0].flatten()*a
                op=Affine(a,b)
            elif typ == "dagnn.ReLU":
                op=torch.nn.ReLU()
            elif typ == "dagnn.Pooling":
                assert block["method"]=="max"
                pad=ints(block["pad"]); assert len(set(pad))==1
                op=torch.nn.MaxPool2d(tuple(ints(block["poolSize"])),tuple(ints(block["stride"])),padding=pad[0])
            else:
                assert typ in ("dagnn.Sum","My_sum_layer","dagnn.DropOut","My_copy_layer"),typ
                op=torch.nn.Identity()
            self.add_module(f"op{i}",op)
            self.steps.append((typ,inputs,outputs,f"op{i}"))
        self.output_names=seq(layers["outputs"][-1])
        # Keep all backbone branch outputs for the refinement network.
        self.ref_outputs = graph["layers"]["outputs"][-4:]
    def forward(self, values):
        values = dict(values)
        for typ,inputs,outputs,name in self.steps:
            x=[values[k] for k in inputs]
            if typ == "My_sum_layer":
                shape = (max(i.shape[2] for i in x),max(i.shape[3] for i in x))
                y=sum(bicubic(i,shape) for i in x)
            elif typ == "dagnn.Sum":
                y=sum(x)
            else:
                y=getattr(self,name)(x[0])
            assert len(outputs)==1
            values[outputs[0]]=y
        return values

class Affine(torch.nn.Module):
    def __init__(self,a,b):
        super().__init__()
        self.register_buffer("scale",torch.from_numpy(a.copy()).reshape(1,-1,1,1))
        self.register_buffer("offset",torch.from_numpy(b.copy()).reshape(1,-1,1,1))
    def forward(self,x):
        return x*self.scale+self.offset

class Hands(torch.nn.Module):
    def __init__(self):
        super().__init__()
        data=json.loads((ROOT/"hand-net-L.json").read_text())
        groups=data["group_infos"]
        with h5py.File(ROOT/"weights/hand-refinenet-eyth.mat") as f:
            self.backbone=DAG(groups[2]["dag_net"],f)
            self.refinement=DAG(groups[3]["dag_net"],f)
            w=f["#refs#/F/filters"][()].transpose(0,1,3,2).copy()
            b=np.asarray(json.loads((ROOT/"hand-net-D.json").read_text())["layers"][1]["biases"],dtype=np.float32).flatten()
            self.final=torch.nn.Conv2d(256,3,3,padding=1)
            self.final.weight.data.copy_(torch.from_numpy(w))
            self.final.bias.data.copy_(torch.from_numpy(b))
    def forward(self,image):
        values=self.backbone({"data":image})
        mapping={f"p_ims1d2_outl{i+1}":values[name] for i,name in enumerate(self.backbone.ref_outputs)}
        values=self.refinement(mapping)
        logits=self.final(values["mflow_conv_g4_b3_joint_varout"])
        logits=bicubic(logits,image.shape[2:])
        return logits.softmax(1)[:,1:2]

if __name__ == "__main__":
    import cv2,time
    torch.set_num_threads(4)
    net=Hands().eval()
    print("loaded",sum(p.numel() for p in net.parameters()),flush=True)
    for name,path in [("full",ROOT/"egohos-reference/testimages/images/thu_draw_paper_RGB_draw_paper_lzy_2_38.jpg"),
                      ("partial",ROOT/"partial-fingers-input.jpg")]:
        bgr=cv2.imread(str(path)); rgb=cv2.cvtColor(bgr,cv2.COLOR_BGR2RGB)
        rgb=cv2.resize(rgb,(512,512),interpolation=cv2.INTER_LINEAR)
        x=torch.from_numpy(rgb.transpose(2,0,1).copy()).unsqueeze(0).float()-128
        start=time.perf_counter()
        with torch.inference_mode(): y=net(x).numpy()
        print(name,y.min(),y.max(),np.mean(y>.5),time.perf_counter()-start,flush=True)
        np.savez(ROOT/f"converted/fingers-{name}-reference.npz",image=x.numpy(),reference=y)
        cv2.imwrite(str(ROOT/f"converted/fingers-{name}-input.jpg"),rgb[:,:,::-1])
        cv2.imwrite(str(ROOT/f"converted/fingers-{name}-mask.png"),(y[0,0]*255).astype(np.uint8))
        overlay=rgb.copy()
        overlay[y[0,0]>.5]=overlay[y[0,0]>.5]*.5+np.array([255,0,0])*.5
        cv2.imwrite(str(ROOT/f"converted/fingers-{name}-overlay.jpg"),overlay[:,:,::-1])
