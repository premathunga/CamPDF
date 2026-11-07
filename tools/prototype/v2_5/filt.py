import cv2, numpy as np
def bgmap(page, long=80):
    h,w=page.shape[:2]; s=long/max(h,w)
    sw,sh=max(16,int(round(w*s))),max(16,int(round(h*s)))
    sm=cv2.resize(page,(sw,sh),interpolation=cv2.INTER_AREA).astype(np.float32)
    out=[]
    for c in range(3):
        a=cv2.dilate(sm[...,c],np.ones((9,9),np.uint8))
        a=cv2.blur(a,(7,7),borderType=cv2.BORDER_REPLICATE)
        a=cv2.blur(a,(5,5),borderType=cv2.BORDER_REPLICATE)
        out.append(a)
    return np.stack(out,-1)   # BGR order
def luma_bgr(a): return a[...,2]*0.299+a[...,1]*0.587+a[...,0]*0.114
def severity(bg):
    L=luma_bgr(bg).ravel(); p5,p95=np.percentile(L,5),np.percentile(L,95)
    sev=1-p5/max(p95,1)
    t=np.clip((sev-0.06)/(0.40-0.06),0,1); s=t*t*(3-2*t)
    return sev,s
if __name__=='__main__':
    for i in range(1,6):
        pg=cv2.imread('page%d.png'%i); bg=bgmap(pg); sev,s=severity(bg)
        print("page%d  p5/p95 severity %.3f -> strength s=%.2f"%(i,sev,s))
