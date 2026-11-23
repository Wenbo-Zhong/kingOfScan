import h5py, numpy as np, json
from pathlib import Path
f = h5py.File(Path(__file__).parent / "weights/hand-refinenet-eyth.mat")
def decode(o, depth=0):
    if isinstance(o, h5py.Reference):
        return decode(f[o], depth)
    if depth > 10:
        return {"ref": o.name}
    if isinstance(o, h5py.Group):
        return {k: decode(v, depth+1) for k,v in o.items()}
    if o.attrs.get("MATLAB_class") == b"char":
        return "".join(chr(i) for i in o[()].flatten())
    if o.dtype == h5py.ref_dtype:
        v = [decode(r, depth+1) for r in o[()].flatten()]
        return v[0] if len(v)==1 else v
    if o.size > 50:
        return {"ref": o.name, "shape": o.shape, "dtype": str(o.dtype)}
    return o[()].tolist()
for key in ["c", "n", "D", "L", "b"]:
    Path(__file__).with_name(f"hand-net-{key}.json").write_text(json.dumps(decode(f["#refs#/"+key]), indent=2))
print("decoded MCOS payloads")
