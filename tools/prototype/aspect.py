import numpy as np

def project(P, f, u0, v0, R, t):
    Xc = R @ P.T + t.reshape(3,1)
    return np.stack([f*Xc[0]/Xc[2]+u0, f*Xc[1]/Xc[2]+v0], axis=1)

def rot(ax, ay, az):
    cx,sx=np.cos(ax),np.sin(ax); cy,sy=np.cos(ay),np.sin(ay); cz,sz=np.cos(az),np.sin(az)
    Rx=np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])
    Ry=np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]])
    Rz=np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])
    return Rz@Ry@Rx

def cross(a,b): return np.cross(a,b)

def est_ratio(tl,tr,br,bl,u0,v0):
    # m1=TL m2=TR m3=BL m4=BR, relative-free homogeneous (x,y,1) in pixels
    m1=np.array([tl[0],tl[1],1.0]); m2=np.array([tr[0],tr[1],1.0])
    m3=np.array([bl[0],bl[1],1.0]); m4=np.array([br[0],br[1],1.0])
    k2=np.dot(cross(m1,m4),m3)/np.dot(cross(m2,m4),m3)
    k3=np.dot(cross(m1,m4),m2)/np.dot(cross(m3,m4),m2)
    n2=k2*m2-m1; n3=k3*m3-m1
    n21,n22,n23=n2; n31,n32,n33=n3
    if abs(n23)<1e-9 or abs(n33)<1e-9: return None
    f2=-1.0/(n23*n33)*((n21*n31-(n21*n33+n23*n31)*u0+n23*n33*u0*u0)+(n22*n32-(n22*n33+n23*n32)*v0+n23*n33*v0*v0))
    if not np.isfinite(f2) or f2<=0: return None
    f=np.sqrt(f2)
    def q(n):
        return ((n[0]-u0*n[2])**2+(n[1]-v0*n[2])**2)/f2+n[2]**2
    r=np.sqrt(q(n2)/q(n3))
    return r,f

# A4 sheet 210x297 (w/h = 0.7071), centred at origin, z=0 plane
W,H=210.0,297.0
P=np.array([[-W/2,-H/2,0],[W/2,-H/2,0],[W/2,H/2,0],[-W/2,H/2,0]])  # TL,TR,BR,BL
f_true=1500.0; u0,v0=1000.0,750.0
rng=np.random.default_rng(1)
errs=[];naive=[]
for i in range(200):
    R=rot(rng.uniform(-.5,.5),rng.uniform(-.5,.5),rng.uniform(-.3,.3))
    t=np.array([rng.uniform(-30,30),rng.uniform(-30,30),rng.uniform(450,800)])
    pts=project(P,f_true,u0,v0,R,t)
    pts+=rng.normal(0,0.5,pts.shape)  # pixel noise
    res=est_ratio(pts[0],pts[1],pts[2],pts[3],u0,v0)
    if res is None: continue
    r,f=res
    errs.append(abs(r-W/H)/(W/H))
    # naive: avg edge lengths
    w=(np.linalg.norm(pts[1]-pts[0])+np.linalg.norm(pts[2]-pts[3]))/2
    h=(np.linalg.norm(pts[3]-pts[0])+np.linalg.norm(pts[2]-pts[1]))/2
    naive.append(abs(w/h-W/H)/(W/H))
print("estimated ok:",len(errs),"/200")
print("zhang-he median rel err %.3f%%  p95 %.3f%%"%(100*np.median(errs),100*np.percentile(errs,95)))
print("naive    median rel err %.3f%%  p95 %.3f%%"%(100*np.median(naive),100*np.percentile(naive,95)))
