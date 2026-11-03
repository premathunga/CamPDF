import numpy as np, cv2
from detect import *
rng=np.random.default_rng(7)
def make_scene(kind,seed):
    rng=np.random.default_rng(seed)
    W,H=1200,1600
    # paper texture w/ text lines
    pw,ph=840,1188
    paper=np.full((ph,pw,3),235,np.uint8)
    for y in range(120,ph-100,34):
        x=100
        while x<pw-120:
            L=int(rng.integers(30,140)); cv2.line(paper,(x,y),(min(x+L,pw-100),y),(40,40,40),int(rng.integers(3,6))); x+=L+int(rng.integers(12,28))
    cv2.rectangle(paper,(0,0),(pw-1,ph-1),(200,200,200),2)
    # background
    if kind=='dark': bg=np.full((H,W,3),(60,70,80),np.uint8)
    elif kind=='wood': bg=np.full((H,W,3),(120,160,200),np.uint8)   # tan (BGR) fairly bright
    elif kind=='bright_table': bg=np.full((H,W,3),(205,210,215),np.uint8) # light table, low contrast
    elif kind=='finger': bg=np.full((H,W,3),(60,70,80),np.uint8)
    bg=(bg.astype(np.int32)+rng.normal(0,6,bg.shape)).clip(0,255).astype(np.uint8)
    src=np.float32([[0,0],[pw,0],[pw,ph],[0,ph]])
    cx,cy=W/2,H/2
    j=lambda a: rng.uniform(-a,a)
    dst=np.float32([[cx-380+j(60),cy-520+j(60)],[cx+380+j(60),cy-540+j(60)],[cx+420+j(50),cy+560+j(50)],[cx-400+j(50),cy+540+j(50)]])
    M=cv2.getPerspectiveTransform(src,dst)
    warped=cv2.warpPerspective(paper,M,(W,H),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
    mask=cv2.warpPerspective(np.full((ph,pw),255,np.uint8),M,(W,H))
    img=bg.copy(); img[mask>0]=warped[mask>0]
    # shadow gradient over whole image
    grad=np.linspace(1.0,0.62,W)[None,:,None]
    img=(img*grad).clip(0,255).astype(np.uint8)
    if kind=='finger':
        cv2.ellipse(img,(int(dst[3][0]+40),int(dst[3][1]-250)),(90,40),30,0,360,(150,170,200),-1)
    return img,(dst/np.float32([W,H]))

def corner_err(pred,truth):
    return float(np.mean(np.linalg.norm(pred-truth,axis=1)))

for kind in ['dark','wood','bright_table','finger']:
    eo=[];en=[];fails=0
    for seed in range(12):
        img,truth=make_scene(kind,seed)
        # old returns TL,TR,BR,BL as array already
        o=old_detect(img); n,ok=detect(img)
        eo.append(corner_err(o,truth)); en.append(corner_err(n,truth))
        if not ok: fails+=1
    print("%-13s old mean err %.3f (max %.3f) | new mean err %.3f (max %.3f) fallbacks %d/12"%(kind,np.mean(eo),np.max(eo),np.mean(en),np.max(en),fails))
img,truth=make_scene('wood',3); n,ok=detect(img)
vis=img.copy()
for p in (n*np.array([img.shape[1],img.shape[0]])).astype(int): cv2.circle(vis,tuple(p),14,(0,255,0),-1)

