from pathlib import Path
import numpy as np,cv2,shutil
from mobile_models import ROOT
output=Path("/tmp/kingscan-ai-fixtures");output.mkdir(exist_ok=True)
for name,edge in [("docres",256),("esdnet",512),("handwriting",512),("fingers",512),("watermark",256)]:
    data=np.load(ROOT/f"converted/{name}-{edge}-reference.npz")
    data["image"].astype("<f4").tofile(output/f"{name}-input.f32")
    data["reference"].astype("<f4").tofile(output/f"{name}-reference.f32")
    shutil.copy2(ROOT/f"converted/{name}-{edge}-input.png",output/f"{name}-input.png")
    if name=="docres":image=cv2.imread(str(ROOT/"converted/docres-tile-input.png"))
    elif name=="esdnet":image=cv2.imread(str(ROOT/"converted/esdnet-tile-input.png"))
    elif name=="handwriting":
        image=cv2.imread(str(ROOT/"Handwriting-DeepLab/images/11.jpg"));image=image[:,:image.shape[1]//2]
    elif name=="fingers":image=cv2.imread(str(ROOT/"partial-fingers-input.jpg"))
    else:image=cv2.imread(str(ROOT/"converted/watermark-256-input.png"))
    cv2.imwrite(str(output/f"{name}-page.png"),image)

def hashunit(seed,x,y,c,salt):
    v=(seed^(x*0x1f123bb5)^(y*0x5f356495)^(c*0x6c8e9cf5)^salt)&0xffffffff
    v=((v^(v>>16))*-2048144789)&0xffffffff
    v=((v^(v>>13))*-1028477387)&0xffffffff
    v=v^(v>>16)
    return (v+.5)/4294967296.
noise=np.array([np.sqrt(-2*np.log(hashunit(20261123,i%37,i//37,i%3,0)))*
    np.cos(2*np.pi*hashunit(20261123,i%37,i//37,i%3,0x13579bdf)) for i in range(148)],dtype="<f4")
noise.tofile(output/"noise-reference.f32")
print(output)
