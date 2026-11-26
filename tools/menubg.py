import numpy as np, math
from PIL import Image, ImageDraw, ImageFilter
from preview import load   # reuse the GLB reader
W,H=1920,1080
tris=load('monster.glb')
# silhouette mask (front view, orthographic)
S=0.78*H/2.3; cx=0.72*W; fy=0.93*H
mask=Image.new('L',(W,H),0); md=ImageDraw.Draw(mask)
for P,I,col,em in tris:
    xs=cx+P[:,0]*S; ys=fy-P[:,1]*S
    for t in I:
        md.polygon([(xs[t[0]],ys[t[0]]),(xs[t[1]],ys[t[1]]),(xs[t[2]],ys[t[2]])],fill=255)
mask=mask.filter(ImageFilter.GaussianBlur(1.2))
rng=np.random.default_rng(3)
# base wall + floor
y=np.linspace(0,1,H)[:,None]
base=np.zeros((H,W,3),np.float32)
base[:]=np.array([7,9,13])+np.array([10,10,14])*y[...,None]
# door light
door=Image.new('RGB',(W,H),(0,0,0)); dd=ImageDraw.Draw(door)
dw=0.40*S*1.1/ (2.3/ (0.78*H)) if False else int(1.25*S); dh=int(2.6*S)
x0,x1=int(cx-dw/2),int(cx+dw/2); y1=int(fy); y0=y1-dh
grad=np.zeros((dh,dw,3),np.float32)
t=np.linspace(0,1,dh)[:,None,None]
grad[:]=np.array([70,42,20])*(1-t)+np.array([225,160,80])*t
door.paste(Image.fromarray(grad.astype(np.uint8)),(x0,y0))
# floor spill
spill=Image.new('L',(W,H),0); ImageDraw.Draw(spill).polygon([(x0,y1),(x1,y1),(x1+420,H),(x0-420,H)],fill=150)
spill=spill.filter(ImageFilter.GaussianBlur(40))
glow=door.filter(ImageFilter.GaussianBlur(70))
img=base+np.array(glow,np.float32)*0.55+np.array(door,np.float32)
sp=np.array(spill,np.float32)[...,None]/255.0
img+=sp*np.array([120,80,40])*0.8
# door frame
fr=Image.fromarray(np.clip(img,0,255).astype(np.uint8)); fd=ImageDraw.Draw(fr)
fd.rectangle([x0-14,y0-14,x1+14,y0],fill=(10,8,8)); fd.rectangle([x0-14,y0,x0,y1],fill=(10,8,8)); fd.rectangle([x1,y0,x1+14,y1],fill=(10,8,8))
img=np.array(fr,np.float32)
# reflection of monster on the floor
refl=mask.transpose(Image.FLIP_TOP_BOTTOM)
refl=refl.crop((0,0,W,H)).transform((W,H),Image.AFFINE,(1,0,0,0,1,-(2*int(fy)-H) if False else 0))
# monster silhouette (dark, with faint rim from door light)
m=np.array(mask,np.float32)[...,None]/255.0
rim=np.array(mask.filter(ImageFilter.GaussianBlur(5)),np.float32)[...,None]/255.0
img=img*(1-m)+np.array([2,2,3])*m
img+=np.clip(rim-m,0,1)*np.array([90,60,30])*0.5
# fog
fog=rng.random((H//40,W//40)).astype(np.float32)
fog=np.array(Image.fromarray((fog*255).astype(np.uint8)).resize((W,H),Image.BICUBIC).filter(ImageFilter.GaussianBlur(30)),np.float32)/255.0
img+=fog[...,None]*np.array([24,26,32])*(0.4+0.6*y[...,None])
# darken left for menu text, vignette, grain
xx=np.linspace(0,1,W)[None,:,None]
img*=np.clip(0.45+xx*1.2,0,1)
yy,xg=np.mgrid[0:H,0:W]; d=np.sqrt(((xg-W*0.6)/(W*0.75))**2+((yy-H*0.5)/(H*0.8))**2)
img*=np.clip(1.15-d*0.9,0.15,1)[...,None]
img+=rng.normal(0,4,(H,W,1))
Image.fromarray(np.clip(img,0,255).astype(np.uint8)).save('/home/claude/proj/app/src/main/assets/menu_bg.jpg',quality=86)
Image.fromarray(np.clip(img,0,255).astype(np.uint8)).resize((960,540)).save('/home/claude/menu_prev.png')
import os;print(os.path.getsize('/home/claude/proj/app/src/main/assets/menu_bg.jpg')//1024,'KB')
