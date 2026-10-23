"""Software rasterizer for the exact SGCH mesh, skinning, texture and cull batches.
Not an emulator: this checks content and bone transforms independently of the 3DS driver.
"""
from pathlib import Path
import struct,math
import numpy as np
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
def load(path):
    data=path.read_bytes();_,ver,skel,nb,nv,ntex,nbatch,crc,size=struct.unpack_from('<4s8I',data)
    offset=36;bones=[]
    for i in range(nb):
        name,parent,*m=struct.unpack_from('<32si32f',data,offset);offset+=164
        local=np.array(m[:16]).reshape(4,4);inv=np.array(m[16:]).reshape(4,4)
        bones.append((name.split(b'\0')[0].decode(),parent,local,inv))
    batches=[]
    for i in range(nbatch):batches.append(struct.unpack_from('<3I',data,offset));offset+=12
    dtype=np.dtype([('p','<f4',3),('uv','<f4',2),('color','<f4',4),('bone','u1',4),('weight','<f4',4)])
    vs=np.frombuffer(data,dtype=dtype,count=nv,offset=offset);offset+=nv*56
    tex=np.frombuffer(data[offset:],dtype=np.uint8).reshape(-1,4)
    ys,xs=np.mgrid[0:ntex,0:ntex];morton=np.zeros_like(xs)
    for b in range(3):morton|=((xs>>b)&1)<<(2*b);morton|=((ys>>b)&1)<<(2*b+1)
    indices=(ys//8)*(ntex//8)*64+(xs//8)*64+morton
    atlas=tex[indices][:,:,[3,2,1,0]]
    return bones,batches,vs,atlas
def rotation(x,y,z):
    cx,sx,cy,sy,cz,sz=math.cos(x),math.sin(x),math.cos(y),math.sin(y),math.cos(z),math.sin(z)
    return np.array([[cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx,0],[sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx,0],[-sy,cy*sx,cy*cx,0],[0,0,0,1]])
def pose(bones,state,t):
    rest=[];world=[];skin=[]
    for name,parent,local,inv in bones:
        rest.append(local if parent<0 else rest[parent]@local)
        x=y=z=0;s=math.sin(t*1.5);q=math.sin(t*.63)
        if name=='upper_arm.L':z=-1.18
        if name=='upper_arm.R':z=1.18
        if name=='chest':z=q*.04;x=s*.01
        if name=='head':y=q*.18*.4;x=s*.18*.3+( .12 if state==1 else 0);z=.18 if state==5 else 0
        if name.startswith('hair.'):x=s*.02;z=q*.02
        if state in (3,4):
            if name=='upper_arm.L':z+=1.1*(.6+.25*s)*.8
            if name=='forearm.L':z=.4
            if name=='hand.L':y=s*.32
            if state==3 and name=='upper_arm.R':z-=1.1*.6*.8
        if state==2 and name=='chest':z+=s*.064
        basis=np.eye(4);basis[:3,:3]=rest[-1][:3,:3]/np.linalg.norm(rest[-1][:3,:3],axis=0)
        lm=local@np.linalg.inv(basis)@rotation(x,y,z)@basis
        world.append(lm if parent<0 else world[parent]@lm);skin.append(world[-1]@inv)
    return np.array(skin)
def render(bones,batches,vs,atlas,state,t,w=480,h=480,cull=True):
    skins=pose(bones,state,t);points=np.c_[vs['p'],np.ones(len(vs))]
    transformed=np.einsum('nkij,nj,nk->ni',skins[vs['bone']],points,vs['weight'])[:,:3]
    xy=np.c_[w*.5+transformed[:,0]*h*1.1,h*.46-transformed[:,1]*h*1.1]
    image=np.empty((h,w,4),dtype=np.uint8);image[:]=[18,22,38,255];depth=np.full((h,w),-np.inf)
    ntex=len(atlas)
    for start,count,mode in batches:
        for i in range(start,start+count,3):
            p=xy[i:i+3];z=transformed[i:i+3,2];uv=vs['uv'][i:i+3]
            edge1=p[1]-p[0];edge2=p[2]-p[0];area=edge1[0]*edge2[1]-edge1[1]*edge2[0]
            if abs(area)<1e-6 or (cull and mode==1 and area>=0) or (cull and mode==2 and area<=0):continue
            lo=np.maximum(0,np.floor(p.min(axis=0)).astype(int));hi=np.minimum([w-1,h-1],np.ceil(p.max(axis=0)).astype(int))
            if np.any(lo>hi):continue
            yy,xx=np.mgrid[lo[1]:hi[1]+1,lo[0]:hi[0]+1];px=xx+.5;py=yy+.5
            a=((p[1,0]-px)*(p[2,1]-py)-(p[1,1]-py)*(p[2,0]-px))/area
            b=((p[2,0]-px)*(p[0,1]-py)-(p[2,1]-py)*(p[0,0]-px))/area;c=1-a-b
            zz=a*z[0]+b*z[1]+c*z[2]
            u=a*uv[0,0]+b*uv[1,0]+c*uv[2,0];v=a*uv[0,1]+b*uv[1,1]+c*uv[2,1]
            color=atlas[np.clip((v*ntex).astype(int),0,ntex-1),np.clip((u*ntex).astype(int),0,ntex-1)]
            d=depth[lo[1]:hi[1]+1,lo[0]:hi[0]+1];out=image[lo[1]:hi[1]+1,lo[0]:hi[0]+1]
            visible=(a>=0)&(b>=0)&(c>=0)&(zz>d)&(color[:,:,3]>8)
            out[visible]=color[visible];d[visible]=zz[visible]
    return Image.fromarray(image,'RGBA')
if __name__=='__main__':
    bones,batches,vs,atlas=load(ROOT/'character'/'model.sgch')
    sheet=Image.new('RGB',(1440,1020),(18,22,38));draw=ImageDraw.Draw(sheet)
    labels=['IDLE','FOCUS','BREAK','SUCCESS','ENCOURAGE','SLEEP']
    for state in range(6):
        panel=render(bones,batches,vs,atlas,state,1.5)
        x=(state%3)*480;y=(state//3)*510;sheet.paste(panel,(x,y+30));draw.text((x+18,y+10),labels[state],fill=(240,240,255))
    sheet.save(ROOT/'character'/'preview.png')
    Image.fromarray(atlas).save(ROOT/'character'/'atlas.png')
    print('Saved exact-mesh software preview')
