import numpy as np, cv2, math
from collections import deque

def otsu(vals, n_total):
    hist=np.bincount(vals.ravel(),minlength=256)
    total=vals.size
    s=sum(i*hist[i] for i in range(256)); sB=0; wB=0; best=-1; thr=128
    for t in range(256):
        wB+=hist[t]
        if wB==0: continue
        wF=total-wB
        if wF==0: break
        sB+=t*hist[t]
        mB=sB/wB; mF=(s-sB)/wF
        v=wB*wF*(mB-mF)**2
        if v>best: best=v; thr=t
    return thr

def box_blur(a,r):
    return cv2.blur(a,(2*r+1,2*r+1),borderType=cv2.BORDER_REPLICATE)

def largest_component(mask):
    h,w=mask.shape
    n,lab,stats,_=cv2.connectedComponentsWithStats(mask.astype(np.uint8),connectivity=4)
    if n<=1: return None,0
    k=1+np.argmax(stats[1:,cv2.CC_STAT_AREA])
    return (lab==k),stats[k,cv2.CC_STAT_AREA]

def fill_holes(comp):
    h,w=comp.shape
    inv=(~comp).astype(np.uint8)
    n,lab,_,_=cv2.connectedComponentsWithStats(inv,connectivity=4)
    # background = labels touching border
    border=set(lab[0,:])|set(lab[-1,:])|set(lab[:,0])|set(lab[:,-1])
    out=comp.copy()
    for k in range(1,n):
        if k not in border: out|=(lab==k)
    return out

def convex_hull(pts):
    pts=sorted(set(pts))
    if len(pts)<=2: return pts
    def cr(o,a,b): return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    lo=[]
    for p in pts:
        while len(lo)>=2 and cr(lo[-2],lo[-1],p)<=0: lo.pop()
        lo.append(p)
    up=[]
    for p in reversed(pts):
        while len(up)>=2 and cr(up[-2],up[-1],p)<=0: up.pop()
        up.append(p)
    return lo[:-1]+up[:-1]

def poly_area(p):
    s=0
    for i in range(len(p)):
        x1,y1=p[i]; x2,y2=p[(i+1)%len(p)]
        s+=x1*y2-x2*y1
    return abs(s)/2

def reduce_hull(h,maxn):
    h=list(h)
    while len(h)>maxn:
        best=None;bi=-1
        for i in range(len(h)):
            a=h[i-1];b=h[i];c=h[(i+1)%len(h)]
            ar=abs((b[0]-a[0])*(c[1]-a[1])-(c[0]-a[0])*(b[1]-a[1]))/2
            if best is None or ar<best: best=ar;bi=i
        h.pop(bi)
    return h

def best_quad(h):
    h=reduce_hull(h,14); n=len(h)
    if n<4: return None
    best=-1;bq=None
    for a in range(n-3):
        for b in range(a+1,n-2):
            for c in range(b+1,n-1):
                for d in range(c+1,n):
                    q=[h[a],h[b],h[c],h[d]]
                    ar=poly_area(q)
                    if ar>best: best=ar;bq=q
    return bq

def order_quad(q):
    cx=sum(p[0] for p in q)/4; cy=sum(p[1] for p in q)/4
    q=sorted(q,key=lambda p: math.atan2(p[1]-cy,p[0]-cx))
    k=min(range(4),key=lambda i:q[i][0]+q[i][1])
    return [q[(k+i)%4] for i in range(4)]

def angles_ok(q):
    for i in range(4):
        a=np.array(q[i-1])-np.array(q[i]); b=np.array(q[(i+1)%4])-np.array(q[i])
        c=np.dot(a,b)/(np.linalg.norm(a)*np.linalg.norm(b)+1e-9)
        ang=math.degrees(math.acos(max(-1,min(1,c))))
        if ang<50 or ang>130: return False
    return True

def candidate(mask,w,h):
    comp,size=largest_component(mask)
    if comp is None: return None
    comp=fill_holes(comp)
    area=comp.sum()
    if area<0.12*w*h or area>0.97*w*h: return None
    # boundary pixels
    er=cv2.erode(comp.astype(np.uint8),np.ones((3,3),np.uint8),borderType=cv2.BORDER_CONSTANT,borderValue=0).astype(bool)
    ys,xs=np.nonzero(comp&~er)
    hull=convex_hull(list(zip(xs.tolist(),ys.tolist())))
    q=best_quad(hull)
    if q is None: return None
    q=order_quad(q)
    if not angles_ok(q): return None
    qa=poly_area(q)
    if qa<0.12*w*h: return None
    fill=min(1.0,area/qa)
    if fill<0.75: return None
    score=fill*fill*math.sqrt(qa/(w*h))
    for (x,y) in q:
        if x<=1 or y<=1 or x>=w-2 or y>=h-2: score*=0.8
    return score,q

def detect(img_bgr):
    h0,w0=img_bgr.shape[:2]
    s=320/max(h0,w0)
    img=cv2.resize(img_bgr,(int(w0*s),int(h0*s)),interpolation=cv2.INTER_AREA)
    h,w=img.shape[:2]
    b,g_,r=[img[:,:,i].astype(np.int32) for i in (0,1,2)]
    g=(r*299+g_*587+b*114)//1000
    g=box_blur(g.astype(np.float32),2).astype(np.int32)
    masks=[]
    thr=otsu(g,w*h); masks.append(g>thr)
    # colour distance from border colour (median of outer frame)
    m=max(2,int(0.04*min(w,h)))
    frame=np.zeros((h,w),bool); frame[:m,:]=1;frame[-m:,:]=1;frame[:,:m]=1;frame[:,-m:]=1
    d=np.zeros((h,w),np.int32)
    for ch in (r,g_,b):
        med=int(np.median(ch[frame]))
        d+=np.abs(ch-med)
    d=np.clip(box_blur(d.astype(np.float32),2).astype(np.int32),0,255)
    masks.append(d>otsu(d,w*h))
    chroma=np.maximum(np.maximum(r,g_),b)-np.minimum(np.minimum(r,g_),b)
    chroma=box_blur(chroma.astype(np.float32),2).astype(np.int32)
    ct=otsu(np.clip(chroma,0,255),w*h)
    pm=chroma<=ct
    if pm.any() and (~pm).any() and chroma[~pm].mean()-chroma[pm].mean()>=25: masks.append(pm)
    best=None
    for mk in masks:
        c=candidate(mk,w,h)
        if c and (best is None or c[0]>best[0]): best=c
    fallback=[(0.05,0.05),(0.95,0.05),(0.95,0.95),(0.05,0.95)]
    if best is None: return np.array(fallback,float),False
    return np.array([[(x+0.5)/w,(y+0.5)/h] for x,y in best[1]]),True

def old_detect(img_bgr):
    h0,w0=img_bgr.shape[:2]; s=240/max(h0,w0)
    img=cv2.resize(img_bgr,(int(w0*s),int(h0*s)),interpolation=cv2.INTER_AREA)
    h,w=img.shape[:2]
    g=cv2.cvtColor(img,cv2.COLOR_BGR2GRAY).astype(np.int32)
    thr=otsu(g,w*h)
    comp,size=largest_component(g>thr)
    fb=np.array([(0.05,0.05),(0.95,0.05),(0.95,0.95),(0.05,0.95)],float)
    if comp is None or size<0.15*w*h or size>0.97*w*h: return fb
    ys,xs=np.nonzero(comp)
    s_=xs+ys; d_=xs-ys
    return np.array([(xs[s_.argmin()]+.5)/w,(ys[s_.argmin()]+.5)/h,
                     (xs[d_.argmax()]+.5)/w,(ys[d_.argmax()]+.5)/h,
                     (xs[s_.argmax()]+.5)/w,(ys[s_.argmax()]+.5)/h,
                     (xs[d_.argmin()]+.5)/w,(ys[d_.argmin()]+.5)/h]).reshape(4,2)
