import cv2, numpy as np
from filt import bgmap, luma_bgr
from finger import finger_mask
def sstep(t): t=np.clip(t,0,1); return t*t*(3-2*t)
def prep(page):
    h,w=page.shape[:2]; bg=bgmap(page); up=cv2.resize(bg,(w,h),interpolation=cv2.INTER_LINEAR)
    paper=np.array([np.percentile(bg[...,c],90) for c in range(3)],np.float32)   # B,G,R
    return up,paper
def severity(bg):
    L=luma_bgr(bg); h,w=L.shape; iy,ix=int(.06*h),int(.06*w); r=(L/np.percentile(L,90))[iy:h-iy,ix:w-ix]
    sev=1-(0.6*np.percentile(r,10)+0.4*np.percentile(r,1))
    return float(sstep((sev-0.05)/0.20))
def normalize(page,up,paper,adaptive=True):
    pf=page.astype(np.float32)
    if adaptive:
        ratio=luma_bgr(up)/ (paper[2]*0.299+paper[1]*0.587+paper[0]*0.114)
        wgt=sstep((np.abs(ratio-1)-0.04)/0.08)[...,None]
        eff=paper+(up-paper)*wgt
    else: eff=up
    return np.minimum(255,pf/np.maximum(eff,30)*255)
def magic(norm):
    b,g,r=[norm[...,i] for i in range(3)]; L=r*.299+g*.587+b*.114
    bp,wp=135.,215.
    tl=np.clip((L-bp)/(wp-bp),0,1); gl=255*tl**1.2; gain=gl/np.maximum(L,1)
    f=norm*gain[...,None]
    t=sstep((L-205)/30)[...,None]
    f=f+(255-f)*0.92*t
    return np.clip(f,0,255)
def enhance_old(page):
    pf=page.astype(np.float32); L=luma_bgr(pf); lo,hi=np.percentile(L,0.5),np.percentile(L,97)
    sc=255/max(hi-lo,40); e=np.clip((pf-lo)*sc,0,255)
    return tone_enh(e)
def tone_enh(e):
    le=luma_bgr(e); boost=np.where(le<130,1.28,np.where(le>210,0.96,1.08))[...,None]
    f=255*(e/255)**boost
    lut=np.where(le<200,200*(le/200)**1.3,le); gain=np.where(le<200,lut/np.maximum(le,1),1.0)[...,None]
    return np.clip(f*gain,0,255)
def enhance_new(page,up,paper):
    pf=page.astype(np.float32)
    paperL=paper[2]*.299+paper[1]*.587+paper[0]*.114
    ratio=luma_bgr(up)/paperL; wgt=sstep((np.abs(ratio-1)-0.04)/0.08)
    effL=paperL+(luma_bgr(up)-paperL)*wgt
    sc=(255/np.maximum(effL,30))[...,None]
    nrm=np.minimum(255,pf*sc)
    L=luma_bgr(pf); lo=np.percentile(L,0.5)*255/paperL; lo=float(np.clip(lo,0,160))
    e=np.clip((nrm-lo)*(255/max(255-lo,40)),0,255)
    return tone_enh(e)
def stats(name,img):
    L=luma_bgr(img.astype(np.float32)); h,w=L.shape; i=L[int(.05*h):int(.95*h),int(.05*w):int(.95*w)]
    paper=i[i>170]
    print("   %-14s paper mean %.0f  paper std %.1f  ink<110 %.2f%%"%(name,paper.mean(),paper.std(),100*(i<110).mean()))
if __name__=='__main__':
    rows=[]
    for k in (3,4,5):
        pg=cv2.imread('page%d.png'%k); up,paper=prep(pg); s=severity(bgmap(pg))
        print("page%d severity strength %.2f"%(k,s))
        a=magic(normalize(pg,up,paper,adaptive=False)); b=magic(normalize(pg,up,paper,adaptive=True))
        e0=enhance_old(pg); e1=enhance_new(pg,up,paper)
        for n,x in (("MAGIC old",a),("MAGIC new",b),("ENHANCE old",e0),("ENHANCE new",e1)): stats(n,x)
        h,w=pg.shape[:2]; tw=int(w*700/h)
        rows.append(np.hstack([cv2.resize(x.astype(np.uint8),(tw,700)) for x in (pg,a,b,e0,e1)]))
    cv2.imwrite('filt_cmp.png',np.vstack(rows) if len({r.shape[1] for r in rows})==1 else rows[0])
    for i,r in enumerate(rows): cv2.imwrite('filt_cmp%d.png'%i,r)
