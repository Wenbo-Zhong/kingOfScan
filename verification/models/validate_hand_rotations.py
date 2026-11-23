import cv2,torch,numpy as np
from hand_refinenet import Hands,ROOT
torch.set_num_threads(4)
net=Hands().eval()
for name,path in [("partial",ROOT/"partial-fingers-input.jpg"),("paper",ROOT/"Handwriting-DeepLab/images/11.jpg")]:
    image=cv2.imread(str(path))[:,:,::-1].copy()
    if name=="paper":image=image[:,:image.shape[1]//2]
    h,w=image.shape[:2];scale=512/max(h,w)
    small=cv2.resize(image,(round(w*scale),round(h*scale)))
    canvas=np.full((512,512,3),128,np.uint8);canvas[:small.shape[0],:small.shape[1]]=small
    masks=[]
    for r in range(4):
        rotated=np.rot90(canvas,r).copy()
        x=torch.from_numpy(rotated.transpose(2,0,1).copy()).unsqueeze(0).float()-128
        with torch.inference_mode():p=net(x).numpy()[0,0]
        masks.append(np.rot90(p,-r).copy())
    p=np.max(masks,0)[:small.shape[0],:small.shape[1]]
    np.save(ROOT/f"converted/fingers-{name}-rotations-mask.npy",p)
    for threshold in [.2,.35,.5]:
        overlay=small.copy();overlay[p>threshold]=overlay[p>threshold]*.5+np.array([255,0,0])*.5
        cv2.imwrite(str(ROOT/f"converted/fingers-{name}-rotations-{threshold}.jpg"),overlay[:,:,::-1])
        print(name,threshold,np.mean(p>threshold),flush=True)
