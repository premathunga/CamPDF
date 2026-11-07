import cv2, numpy as np
def skin_pixels(sm):
    hsv=cv2.cvtColor(sm,cv2.COLOR_BGR2HSV_FULL).astype(np.float32)   # H 0..255 == 0..360 deg
    hdeg=hsv[...,0]*360/256.0; s=hsv[...,1]/255.0; v=hsv[...,2]/255.0
    hue_ok=(hdeg<=40)|(hdeg>=335)
    return hue_ok&(s>=0.18)&(s<=0.72)&(v>=0.25)&(v<=0.97)
def finger_mask(page_bgr,long=200):
    h,w=page_bgr.shape[:2]; s=long/max(h,w)
    sm=cv2.resize(page_bgr,(max(8,int(w*s)),max(8,int(h*s))),interpolation=cv2.INTER_AREA)
    mh,mw=sm.shape[:2]
    sk=skin_pixels(sm)
    frac=sk.mean()
    if frac<0.002 or frac>0.30: return None,frac,sk
    k=np.ones((3,3),np.uint8)
    op=cv2.dilate(cv2.erode(sk.astype(np.uint8),k),k)
    n,lab,stats,_=cv2.connectedComponentsWithStats(op,connectivity=4)
    band=max(2,int(0.03*min(mw,mh)))
    out=np.zeros((mh,mw),np.uint8)
    for i in range(1,n):
        x,y,bw,bh,a=stats[i]
        af=a/(mw*mh)
        if af<0.002 or af>0.12: continue
        touches = x<=band or y<=band or x+bw>=mw-band or y+bh>=mh-band
        if not touches: continue
        if a/(bw*bh)<0.40: continue
        out[lab==i]=1
    if out.sum()==0: return None,frac,sk
    out=cv2.dilate(out,np.ones((7,7),np.uint8))
    return out,frac,sk
if __name__=='__main__':
    rows=[]
    for i in range(1,6):
        pg=cv2.imread('page%d.png'%i)
        m,frac,sk=finger_mask(pg)
        h,w=pg.shape[:2]
        vis=pg.copy()
        if m is not None:
            big=cv2.resize(m,(w,h),interpolation=cv2.INTER_NEAREST)>0
            vis[big]=(0.35*vis[big]+0.65*np.array([0,0,255])).astype(np.uint8)
        print("page%d skin frac %.4f -> mask %s"%(i,frac,"NONE" if m is None else "%.3f of page"%(m.mean())))
        rows.append(cv2.resize(vis,(240,int(240*h/w))))
    H=max(r.shape[0] for r in rows); rows=[cv2.copyMakeBorder(r,0,H-r.shape[0],0,0,cv2.BORDER_CONSTANT) for r in rows]
    cv2.imwrite('finger_vis.png',np.hstack(rows))
