import json,struct,io,sys,numpy as np
from PIL import Image
SRC='/mnt/user-data/uploads/skinny_monster_2_1.glb'
d=open(SRC,'rb').read(); l=struct.unpack('<I',d[12:16])[0]
j=json.loads(d[20:20+l]); B=d[20+l+8:]
def acc(i):
    a=j['accessors'][i]; bv=j['bufferViews'][a['bufferView']]
    ct={5126:np.float32,5125:np.uint32,5123:np.uint16}[a['componentType']]
    nc={'SCALAR':1,'VEC2':2,'VEC3':3}[a['type']]
    o=bv.get('byteOffset',0)+a.get('byteOffset',0)
    isz=np.dtype(ct).itemsize*nc; st=bv.get('byteStride',isz)
    raw=np.frombuffer(B,dtype=np.uint8)
    idx=o+np.arange(a['count'])[:,None]*st+np.arange(isz)[None,:]
    arr=raw[idx].copy().view(ct).reshape(a['count'],nc)
    return arr if nc>1 else arr[:,0]
def mat(n):
    if 'matrix' in n: return np.array(n['matrix'],np.float64).reshape(4,4).T
    return np.eye(4)
# world matrices via tree
nodes=j['nodes']; world={}
def walk(i,P):
    W=P@mat(nodes[i]); world[i]=W
    for c in nodes[i].get('children',[]): walk(c,W)
for r in j['scenes'][0]['nodes']: walk(r,np.eye(4))
groups={0:[],1:[],2:[]}
for i,n in enumerate(nodes):
    if 'mesh' in n:
        p=j['meshes'][n['mesh']]['primitives'][0]
        P=acc(p['attributes']['POSITION']).astype(np.float64); N=acc(p['attributes']['NORMAL']).astype(np.float64); U=acc(p['attributes']['TEXCOORD_0']).astype(np.float64)
        I=acc(p['indices']).astype(np.int64)
        W=world[i]; P=P@W[:3,:3].T+W[:3,3]; N=N@np.linalg.inv(W[:3,:3]); 
        groups[p['material']].append((P,N,U,I))
allp=np.concatenate([g[0] for k in groups for g in groups[k]])
mn,mx=allp.min(0),allp.max(0); print('world bbox',mn,mx)
H=mx[1]-mn[1]; S=2.3/H; cx=(mn[0]+mx[0])/2; cz=(mn[2]+mx[2])/2
def decimate(P,N,U,I,cell):
    q=np.floor(P/cell).astype(np.int64)
    key=q[:,0]*73856093 ^ q[:,1]*19349663 ^ q[:,2]*83492791
    uk,inv=np.unique(key,return_inverse=True)
    cnt=np.bincount(inv).astype(np.float64)
    def avg(A): 
        out=np.zeros((len(uk),A.shape[1]))
        for c in range(A.shape[1]): out[:,c]=np.bincount(inv,weights=A[:,c])/cnt
        return out
    P2,N2,U2=avg(P),avg(N),avg(U)
    T=inv[I].reshape(-1,3)
    ok=(T[:,0]!=T[:,1])&(T[:,1]!=T[:,2])&(T[:,0]!=T[:,2]); T=T[ok]
    T=np.unique(np.sort(T,axis=1)*0+T,axis=0) if False else T
    return P2,N2,U2,T
def run(cell):
    out={}; tot=0
    for k,g in groups.items():
        Ps,Ns,Us,Ts=[],[],[],[]; base=0
        for (P,N,U,I) in g:
            a,b,c,t=decimate(P,N,U,I,cell); Ps.append(a);Ns.append(b);Us.append(c);Ts.append(t+base);base+=len(a)
        if Ps: out[k]=(np.concatenate(Ps),np.concatenate(Ns),np.concatenate(Us),np.concatenate(Ts)); tot+=len(out[k][3])
    return out,tot
lo,hi=0.002,0.08
for _ in range(14):
    mid=(lo+hi)/2; out,tot=run(mid*(mx-mn).max()/1.0*0+mid)
    print(round(mid,4),tot)
    if tot>110000: lo=mid
    else: hi=mid
out,tot=run(hi); print('final',hi,tot)
# normalize, build glb
def img(i):
    bv=j['bufferViews'][j['images'][i]['bufferView']]
    im=Image.open(io.BytesIO(B[bv['byteOffset']:bv['byteOffset']+bv['byteLength']])).convert('RGB')
    o=io.BytesIO(); im.save(o,'JPEG',quality=82); return o.getvalue()
blob=bytearray(); views=[]; accs=[]; prims=[]; imgs=[]
def addbuf(b,target=None):
    while len(blob)%4: blob.append(0)
    off=len(blob); blob.extend(b); v={"buffer":0,"byteOffset":off,"byteLength":len(b)}
    if target: v["target"]=target
    views.append(v); return len(views)-1
def addacc(bv,ct,cnt,typ,mn_=None,mx_=None):
    a={"bufferView":bv,"componentType":ct,"count":cnt,"type":typ}
    if mn_ is not None: a["min"]=mn_;a["max"]=mx_
    accs.append(a); return len(accs)-1
for k,(P,N,U,T) in out.items():
    P=P.copy(); P[:,0]=(P[:,0]-cx)*S; P[:,1]=(P[:,1]-mn[1])*S; P[:,2]=(P[:,2]-cz)*S
    Nn=N/np.maximum(np.linalg.norm(N,axis=1,keepdims=True),1e-9)
    U=U.copy()
    pa=addacc(addbuf(P.astype(np.float32).tobytes(),34962),5126,len(P),'VEC3',P.min(0).tolist(),P.max(0).tolist())
    na=addacc(addbuf(Nn.astype(np.float32).tobytes(),34962),5126,len(P),'VEC3')
    ua=addacc(addbuf(U.astype(np.float32).tobytes(),34962),5126,len(P),'VEC2')
    ia=addacc(addbuf(T.astype(np.uint32).reshape(-1).tobytes(),34963),5125,T.size,'SCALAR')
    prims.append({"attributes":{"POSITION":pa,"NORMAL":na,"TEXCOORD_0":ua},"indices":ia,"material":k})
    print('mat',k,'verts',len(P),'tris',len(T),'bbox',P.min(0),P.max(0))
tex=[];images=[]
for i in (0,1):
    images.append({"bufferView":addbuf(img(i)),"mimeType":"image/jpeg"}); tex.append({"source":i})
materials=[{"name":"m0","pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":0.8}},
 {"name":"m1","pbrMetallicRoughness":{"baseColorFactor":[0.04,0.04,0.045,1],"metallicFactor":0,"roughnessFactor":0.99}},
 {"name":"m2","pbrMetallicRoughness":{"baseColorTexture":{"index":1},"metallicFactor":0,"roughnessFactor":0.8}}]
g={"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"name":"Monster","mesh":0}],
 "meshes":[{"primitives":prims}],"materials":materials,"textures":tex,"images":images,"samplers":[{}],
 "buffers":[{"byteLength":len(blob)}],"bufferViews":views,"accessors":accs}
for t in tex: t["sampler"]=0
js=json.dumps(g,separators=(',',':')).encode()
while len(js)%4: js+=b' '
while len(blob)%4: blob.append(0)
glb=b'glTF'+struct.pack('<II',2,12+8+len(js)+8+len(blob))+struct.pack('<I',len(js))+b'JSON'+js+struct.pack('<I',len(blob))+b'BIN\0'+bytes(blob)
open('/home/claude/proj/app/src/main/assets/monster.glb','wb').write(glb); print('glb',len(glb)/1e6,'MB')
# preview front (looking along -z) 
from PIL import ImageDraw
im=Image.new('RGB',(500,800),(25,25,30)); dr=ImageDraw.Draw(im)
tris=[]
for k,(P,N,U,T) in out.items():
    P=P.copy(); P[:,0]=(P[:,0]-cx)*S; P[:,1]=(P[:,1]-mn[1])*S; P[:,2]=(P[:,2]-cz)*S
    for t in T[::1]:
        a=P[t]; z=a[:,2].mean(); n=np.cross(a[1]-a[0],a[2]-a[0]); 
        tris.append((z,[(250+q[0]*160,760-q[1]*300) for q in a],int(60+60*abs(n[2]/(np.linalg.norm(n)+1e-9)))))
tris.sort(key=lambda x:x[0])
for z,pts,c in tris: dr.polygon(pts,fill=(c,c-20,c-20))
im.save('/home/claude/proj/tools/monster_prev.png')
