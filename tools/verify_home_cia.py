"""Verify our unencrypted homebrew CIA and its actual embedded CGFX."""
import argparse, hashlib, json, struct
from pathlib import Path
from cgfx_inspect import inspect_bytes, compare_source

def need(ok,message):
    if not ok: raise ValueError(message)
def u32(b,p): return struct.unpack_from('<I',b,p)[0]
def align(n): return (n+63)&~63

def lz11(data):
    need(len(data)>=4 and data[0]==0x11,'Expected LZ11 banner')
    length=int.from_bytes(data[1:4],'little'); pos=4
    if not length: length=u32(data,4); pos=8
    need(0<length<=524288,'Invalid decompressed banner length')
    out=bytearray()
    while len(out)<length:
        flags=data[pos]; pos+=1
        for bit in range(7,-1,-1):
            if len(out)>=length: break
            if not flags&(1<<bit):
                out.append(data[pos]); pos+=1; continue
            first=data[pos]; pos+=1; top=first>>4
            if top==0:
                second,third=data[pos:pos+2]; pos+=2
                count=((first&15)<<4 | second>>4)+0x11
                distance=((second&15)<<8 | third)+1
            elif top==1:
                second,third,fourth=data[pos:pos+3]; pos+=3
                count=((first&15)<<12 | second<<4 | third>>4)+0x111
                distance=((third&15)<<8 | fourth)+1
            else:
                second=data[pos]; pos+=1
                count=top+1; distance=((first&15)<<8 | second)+1
            need(distance<=len(out) and len(out)+count<=length,'Invalid LZ11 backreference')
            for _ in range(count): out.append(out[-distance])
    return bytes(out)

def verify(cia,expected_cgfx,source):
    b=cia.read_bytes()
    need(len(b)>=0x2020 and u32(b,0)==0x2020,'Unexpected CIA header')
    content=align(u32(b,0))
    for offset in (8,12,16): content+=align(u32(b,offset))
    content_size=struct.unpack_from('<Q',b,24)[0]
    need(content+content_size<=len(b),'CIA content exceeds file')
    ncch=b[content:content+content_size]
    need(ncch[0x100:0x104]==b'NCCH','NCCH magic missing')
    need(u32(ncch,0x104)*512==len(ncch),'NCCH size mismatch')
    title=struct.unpack_from('<Q',ncch,0x118)[0]
    need(title==0x000400000F764200,'Unexpected title ID')
    need(ncch[0x18f]&4,'Expected unencrypted homebrew NCCH')
    media_unit=512<<ncch[0x18e]
    exefs_start=u32(ncch,0x1a0)*media_unit
    exefs_size=u32(ncch,0x1a4)*media_unit
    need(exefs_start+exefs_size<=len(ncch),'ExeFS outside NCCH')
    exefs=ncch[exefs_start:exefs_start+exefs_size]
    hash_size=u32(ncch,0x1a8)*media_unit
    need(hashlib.sha256(exefs[:hash_size]).digest()==ncch[0x1c0:0x1e0],'ExeFS superblock hash differs')
    ext_size=u32(ncch,0x180)
    need(hashlib.sha256(ncch[0x200:0x200+ext_size]).digest()==ncch[0x160:0x180],'ExHeader hash differs')
    files={}; hashes={}
    for i in range(10):
        entry=exefs[i*16:(i+1)*16]
        name=entry[:8].split(b'\0')[0].decode('ascii')
        if not name: continue
        offset,length=struct.unpack_from('<II',entry,8)
        need(0x200+offset+length<=len(exefs),'ExeFS entry outside filesystem')
        data=exefs[0x200+offset:0x200+offset+length]
        digest=hashlib.sha256(data).digest()
        need(digest==exefs[0xc0+(9-i)*32:0xc0+(10-i)*32],f'ExeFS hash mismatch: {name}')
        files[name]=data; hashes[name]={'bytes':length,'sha256':digest.hex()}
    need({'.code','icon','banner'}.issubset(files),'CIA lacks code/icon/banner')
    icon=files['icon']
    need(icon[:4]==b'SMDH' and len(icon)==0x36c0,'Invalid embedded SMDH')
    flags=u32(icon,0x2028)
    need(flags&0x20 and flags&1 and flags&4,'Missing visible/allow3d/extendedbanner flag')
    banner=files['banner']
    need(banner[:4]==b'CBMD','Invalid banner container')
    cgfx_offset=u32(banner,8); audio_offset=u32(banner,0x84)
    need(0x88<=cgfx_offset<audio_offset<len(banner),'Invalid CBMD offsets')
    cgfx=lz11(banner[cgfx_offset:audio_offset])
    need(cgfx==expected_cgfx.read_bytes(),'Embedded CGFX differs from corrected source')
    report=inspect_bytes(cgfx)
    need(report['passed'],'Embedded CGFX structural checks failed')
    compare_source(report,cgfx,source)
    need(report['passed'],'Embedded CGFX/source comparison failed')
    audio=banner[audio_offset:]
    need(audio[:4]==b'CWAV','Missing banner CWAV')
    info_offset=u32(audio,0x18)
    need(audio[info_offset:info_offset+4]==b'INFO','Missing CWAV INFO')
    rate=u32(audio,info_offset+0xc); frames=u32(audio,info_offset+0x14)
    channels=u32(audio,info_offset+0x1c)
    need(channels==2 and rate>0 and 0<frames/rate<=3,'Invalid banner audio channel count/duration')
    services=[]
    for i in range(32):
        s=ncch[0x450+i*8:0x458+i*8].split(b'\0')[0].decode('ascii')
        if s: services.append(s)
    need({'APT:U','fs:USER','gsp::Gpu','hid:USER','soc:U','ac:u'}.issubset(services),'CIA lacks required UI/SD/network service access')
    return {'schema':1,'passed':True,'ciaBytes':len(b),'ciaSha256':hashlib.sha256(b).hexdigest(),
            'titleId':f'{title:016X}','smdhFlags':f'0x{flags:x}','embeddedCgfxBytes':len(cgfx),
            'embeddedCgfxSha256':hashlib.sha256(cgfx).hexdigest(),'embeddedCgfxMatches':True,
            'exefsHashes':hashes,'audio':{'channels':channels,'sampleRate':rate,'seconds':frames/rate},
            'requiredServicesPresent':True,'hardwareTested':False,
            'scope':'Container, embedded assets and hashes; signatures and hardware execution are not verified'}

def main():
    root=Path(__file__).resolve().parents[1]
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cia',type=Path,default=root/'release/cia/studygadget.cia')
    p.add_argument('--cgfx',type=Path,default=root/'home-menu/banner.cgfx')
    p.add_argument('--source',type=Path,default=root/'home-menu/banner.glb')
    p.add_argument('--report',type=Path,default=root/'release/cia/validation.json')
    a=p.parse_args(); report=verify(a.cia,a.cgfx,a.source)
    a.report.parent.mkdir(parents=True,exist_ok=True)
    a.report.write_text(json.dumps(report,indent=2),encoding='utf8')
    summary={'schema':1,'cgfxBuilt':True,'cgfxInspectionPassed':True,'ciaBuilt':True,
             'ciaContainerInspectionPassed':True,'cgfxBytes':report['embeddedCgfxBytes'],
             'cgfxSha256':report['embeddedCgfxSha256'],'ciaBytes':report['ciaBytes'],
             'ciaSha256':report['ciaSha256'],'hardwareTested':False}
    (root/'home-menu/validation.json').write_text(json.dumps(summary,indent=2),encoding='utf8')
    print(json.dumps(report))

if __name__=='__main__': main()

