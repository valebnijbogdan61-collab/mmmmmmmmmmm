import numpy as np, json, struct, math
def rotm(e):
    ax,ay,az=[math.radians(v) for v in e]
    cx,sx,cy,sy,cz,sz=math.cos(ax),math.sin(ax),math.cos(ay),math.sin(ay),math.cos(az),math.sin(az)
    Rx=np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]]);Ry=np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]]);Rz=np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])
    return Ry@Rx@Rz
def quat(e):
    m=rotm(e); t=m[0,0]+m[1,1]+m[2,2]
    if t>0:
        s=math.sqrt(t+1)*2; return [(m[2,1]-m[1,2])/s,(m[0,2]-m[2,0])/s,(m[1,0]-m[0,1])/s,0.25*s]
    i=int(np.argmax([m[0,0],m[1,1],m[2,2]])); j,k=(i+1)%3,(i+2)%3
    s=math.sqrt(m[i,i]-m[j,j]-m[k,k]+1)*2; q=[0,0,0,0]; q[i]=0.25*s
    q[j]=(m[j,i]+m[i,j])/s; q[k]=(m[k,i]+m[i,k])/s; q[3]=(m[k,j]-m[j,k])/s; return q
class N:
    def __init__(s,name,t=(0,0,0),r=(0,0,0)): s.name=name;s.t=t;s.r=r;s.kids=[];s.geo=[]
    def add(s,n): s.kids.append(n); return n
    def node(s,name,t=(0,0,0),r=(0,0,0)): return s.add(N(name,t,r))
    def _g(s,V,F,color,emis,rot,pos):
        V=np.array(V,float)
        if rot!=(0,0,0): V=V@rotm(rot).T
        V=V+np.array(pos); s.geo.append((V,F,color,emis))
    def box(s,size,pos,color,emis=None,rot=(0,0,0)):
        w,h,d=[v/2 for v in size]; V=[];F=[]
        faces=[((1,0,0),[(w,-h,-d),(w,h,-d),(w,h,d),(w,-h,d)]),((-1,0,0),[(-w,-h,d),(-w,h,d),(-w,h,-d),(-w,-h,-d)]),
               ((0,1,0),[(-w,h,-d),(-w,h,d),(w,h,d),(w,h,-d)]),((0,-1,0),[(-w,-h,d),(-w,-h,-d),(w,-h,-d),(w,-h,d)]),
               ((0,0,1),[(w,-h,d),(w,h,d),(-w,h,d),(-w,-h,d)]),((0,0,-1),[(-w,-h,-d),(-w,h,-d),(w,h,-d),(w,-h,-d)])]
        Ns=[]
        for nrm,q in faces:
            b=len(V); V+=q; Ns+=[nrm]*4; F+=[(b,b+1,b+2),(b,b+2,b+3)]
        s._g2(V,Ns,F,color,emis,rot,pos)
    def _g2(s,V,Ns,F,color,emis,rot,pos):
        V=np.array(V,float);Ns=np.array(Ns,float)
        if rot!=(0,0,0): R=rotm(rot); V=V@R.T; Ns=Ns@R.T
        s.geo.append((V+np.array(pos),Ns,np.array(F),color,emis))
    def cyl(s,r,h,pos,color,emis=None,rot=(0,0,0),n=14):
        V=[];Ns=[];F=[]
        for i in range(n):
            a0=2*math.pi*i/n;a1=2*math.pi*(i+1)/n
            c0,s0,c1,s1=math.cos(a0),math.sin(a0),math.cos(a1),math.sin(a1); b=len(V)
            V+=[(r*c0,-h/2,r*s0),(r*c0,h/2,r*s0),(r*c1,h/2,r*s1),(r*c1,-h/2,r*s1)]
            Ns+=[(c0,0,s0),(c0,0,s0),(c1,0,s1),(c1,0,s1)]; F+=[(b,b+1,b+2),(b,b+2,b+3)]
            b=len(V); V+=[(0,h/2,0),(r*c0,h/2,r*s0),(r*c1,h/2,r*s1)]; Ns+=[(0,1,0)]*3; F+=[(b,b+2,b+1)]
            b=len(V); V+=[(0,-h/2,0),(r*c0,-h/2,r*s0),(r*c1,-h/2,r*s1)]; Ns+=[(0,-1,0)]*3; F+=[(b,b+1,b+2)]
        s._g2(V,Ns,F,color,emis,rot,pos)
    def sph(s,r,pos,color,emis=None,sy=1.0,la=8,lo=12):
        V=[];Ns=[];F=[]
        for i in range(la+1):
            th=math.pi*i/la
            for k in range(lo+1):
                ph=2*math.pi*k/lo; x,y,z=math.sin(th)*math.cos(ph),math.cos(th),math.sin(th)*math.sin(ph)
                V.append((r*x,r*y*sy,r*z)); Ns.append((x,y,z))
        for i in range(la):
            for k in range(lo):
                a=i*(lo+1)+k;b=a+lo+1; F+=[(a,b,a+1),(a+1,b,b+1)]
        s._g2(V,Ns,F,color,emis,(0,0,0),pos)
def write_glb(root,path):
    blob=bytearray();views=[];accs=[];mats=[];matidx={};nodes=[];meshes=[]
    def add(arr,target,ct,typ,mm=False):
        while len(blob)%4: blob.append(0)
        off=len(blob);b=arr.tobytes();blob.extend(b);views.append({"buffer":0,"byteOffset":off,"byteLength":len(b),"target":target})
        a={"bufferView":len(views)-1,"componentType":ct,"count":len(arr) if typ!="SCALAR" else arr.size,"type":typ}
        if mm: a["min"]=arr.min(0).tolist();a["max"]=arr.max(0).tolist()
        accs.append(a);return len(accs)-1
    def mat(color,emis):
        k=(tuple(color),tuple(emis) if emis else None)
        if k not in matidx:
            m={"pbrMetallicRoughness":{"baseColorFactor":list(color)+[1],"metallicFactor":0.0,"roughnessFactor":0.8}}
            if emis: m["emissiveFactor"]=list(emis)
            matidx[k]=len(mats);mats.append(m)
        return matidx[k]
    def emit(n):
        idx=len(nodes);d={"name":n.name};nodes.append(d)
        if n.t!=(0,0,0): d["translation"]=list(map(float,n.t))
        if n.r!=(0,0,0): d["rotation"]=quat(n.r)
        if n.geo:
            groups={}
            for V,Ns,F,c,e in n.geo: groups.setdefault(mat(c,e),[]).append((V,Ns,F))
            prims=[]
            for mi,lst in groups.items():
                P=[];Nn=[];I=[];base=0
                for V,Ns,F in lst: P.append(V);Nn.append(Ns);I.append(np.array(F)+base);base+=len(V)
                P=np.concatenate(P).astype(np.float32);Nn=np.concatenate(Nn).astype(np.float32);I=np.concatenate(I).astype(np.uint32).reshape(-1)
                prims.append({"attributes":{"POSITION":add(P,34962,5126,"VEC3",True),"NORMAL":add(Nn,34962,5126,"VEC3")},"indices":add(I,34963,5125,"SCALAR"),"material":mi})
            meshes.append({"primitives":prims});d["mesh"]=len(meshes)-1
        if n.kids: d["children"]=[emit(k) for k in n.kids]
        return idx
    emit(root)
    g={"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":nodes,"meshes":meshes,"materials":mats,"buffers":[{"byteLength":len(blob)}],"bufferViews":views,"accessors":accs}
    js=json.dumps(g,separators=(',',':')).encode()
    while len(js)%4: js+=b' '
    while len(blob)%4: blob.append(0)
    open(path,'wb').write(b'glTF'+struct.pack('<II',2,12+8+len(js)+8+len(blob))+struct.pack('<I',len(js))+b'JSON'+js+struct.pack('<I',len(blob))+b'BIN\0'+bytes(blob))
    return len(nodes),sum(len(m["primitives"]) for m in meshes)
