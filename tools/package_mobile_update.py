"""Create a bounded, checksum-verified phone import bundle; never include signing keys."""
import argparse,hashlib,json,struct
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED

def package(apk,three_ds,output,version):
    entries=[];files=[]
    for target,path,name,limit in [('3ds',three_ds,'studygadget.3dsx',16*1024*1024),('android',apk,'StudyCompanion.apk',80*1024*1024)]:
        if path is None:continue
        path=Path(path);data=path.read_bytes()
        if not 0<len(data)<=limit:raise ValueError('Invalid file size: '+str(path))
        if target=='3ds':
            if len(data)<32 or data[:4]!=b'3DSX' or b'STUDYGADGET_APP_V1' not in data:raise ValueError('Not an update-capable Study Gadget 3DSX')
            header,reloc,*rest=struct.unpack_from('<HH6I',data,4)
            code,ro,rw,bss=rest[2:]
            if not(32<=header<=512 and 8<=reloc<=512 and code and bss<=rw and header+3*reloc+code+ro+rw-bss<=len(data)):raise ValueError('Invalid 3DSX layout')
        else:
            with ZipFile(path) as z:
                if not {'AndroidManifest.xml','classes.dex'}.issubset(z.namelist()):raise ValueError('Not a base APK')
        entries.append({'target':target,'path':name,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()});files.append((path,name))
    if not entries:raise ValueError('Select at least one executable')
    manifest={'format':'studygadget-update','schema':1,'version':version,'files':entries}
    output=Path(output);output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w',ZIP_DEFLATED,compresslevel=6) as z:
        z.writestr('study-update.json',json.dumps(manifest,ensure_ascii=False));
        for path,name in files:z.write(path,name)
    with ZipFile(output) as z:
        if z.testzip() is not None:raise ValueError('ZIP verification failed')
        for entry in entries:
            data=z.read(entry['path'])
            if len(data)!=entry['size'] or hashlib.sha256(data).hexdigest()!=entry['sha256']:raise ValueError('Bundle checksum differs')
    return manifest

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--apk',type=Path);parser.add_argument('--three-ds',type=Path);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--version',default='local build');args=parser.parse_args()
    print(json.dumps(package(args.apk,args.three_ds,args.output,args.version),ensure_ascii=False))
