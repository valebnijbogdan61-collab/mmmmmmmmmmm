import json,struct,numpy as np,math,sys
from PIL import Image,ImageDraw
A='/home/claude/proj/app/src/main/assets/'
def load(fn):
    d=open(A+fn,'rb').read(); l=struct.unpack('<I',d[12:16])[0]; j=json.loads(d[20:20+l]); B=d[20+l+8:]
    def acc(i):
        a=j['accessors'][i]; bv=j['bufferViews'][a['bufferView']]
        nc={'SCALAR':1,'VEC2':2,'VEC3':3}[a['type']]; ct={5126:np.float32,5125:np.uint32}[a['componentType']]
        return np.frombuffer(B,dtype=ct,count=a['count']*nc,offset=bv['byteOffset']+a.get('byteOffset',0)).reshape(-1,nc) if nc>1 else np.frombuffer(B,dtype=ct,count=a['count'],offset=bv['byteOffset']+a.get('byteOffset',0))
    tris=[]
    def walk(i,T):
        n=j['nodes'][i]; t=np.array(n.get('translation',[0,0,0])); T=T+t
        if 'mesh' in n:
            for p in j['meshes'][n['mesh']]['primitives']:
                P=acc(p['attributes']['POSITION'])+T; I=acc(p['indices']).reshape(-1,3)
                m=j['materials'][p['material']]; col=np.array(m['pbrMetallicRoughness'].get('baseColorFactor',[.5,.5,.5,1])[:3]); em=np.array(m.get('emissiveFactor',[0,0,0]))
                tris.append((P,I,col,em))
        for c in n.get('children',[]): walk(c,T)
    walk(j['scenes'][0]['nodes'][0],np.zeros(3)); return tris
def render(models,eye,yaw,pitch,out,W=360,H=640,fov=72):
    img=Image.new('RGB',(W,H),(4,6,14)); d=ImageDraw.Draw(img)
    f=np.array([math.cos(pitch)*math.sin(yaw),math.sin(pitch),math.cos(pitch)*math.cos(yaw)]); r=np.array([-math.cos(yaw),0,math.sin(yaw)]); u=np.cross(r,f)*-1
    u=np.cross(f,r)
    # right = f x up -> use r given; up = r x f
    u=np.cross(r,f)
    focal=(H/2)/math.tan(math.radians(fov/2)); polys=[]
    for tris in models:
        for P,I,col,em in tris:
            Q=P-eye; cx=Q@r; cy=Q@u; cz=Q@f
            tri=I
            for t in tri:
                z=cz[t]
                if (z<0.1).any(): continue
                xs=W/2+cx[t]/z*focal; ys=H/2-cy[t]/z*focal
                if xs.max()<0 or xs.min()>W or ys.max()<0 or ys.min()>H: continue
                a,b,c=P[t]; n=np.cross(b-a,c-a); nn=np.linalg.norm(n)+1e-9; n=n/nn
                dist=np.linalg.norm(((a+b+c)/3)-eye)
                L=0.25+0.5*abs(n@(-f))*(1/(1+0.05*dist*dist))+0.0
                lamp=max(0,1.2/(1+0.12*np.sum((((a+b+c)/3)-np.array([0,2.1,3]))**2)))
                c3=np.clip(col**(0.7)*(0.25+lamp*0.9)*(0.5+0.5*abs(n@(-f)))+em,0,1)
                polys.append((z.mean(),list(zip(xs,ys)),tuple(int(v*255) for v in c3)))
    polys.sort(key=lambda x:-x[0])
    for z,pts,c in polys: d.polygon(pts,fill=c)
    img.save(out)




