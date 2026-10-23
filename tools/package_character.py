"""Create SD layout and Android bundled model from the exported character."""
from pathlib import Path
import struct,random,json,shutil,hashlib
root=Path(__file__).resolve().parents[1]
source=root/'character'
header=struct.unpack('<4s8I',(source/'model.sgch').read_bytes()[:36])
skel=header[2]
r=random.Random(2107)
lines=[]
for state in range(6):
    for i in range(8):
        calm=state in (1,5)
        values=(state,5+r.random()*10,.35+r.random()*.35 if calm else .7+r.random()*.6,
                .15+r.random()*.25 if calm else .4+r.random()*.5,.015 if calm else .03+r.random()*.07,
                .1+r.random()*.2,.7+r.random()*.3,r.random()*6.28)
        lines.append(' '.join(str(v) if isinstance(v,int) else f'{v:.6f}' for v in values))
(source/'motions.sgmo').write_text(f'SGMO 1 {skel} 1 48\n'+'\n'.join(lines)+'\n',encoding='ascii')
sd=root/'release'/'sd'/'3ds'/'studygadget';sd.mkdir(parents=True,exist_ok=True)
app=root/'assets'/'app'/'src'/'main'/'assets'/'character';app.mkdir(parents=True,exist_ok=True)
for dest in (sd,app):
    for name in ('model.sgch','motions.sgmo','manifest.json'):shutil.copy2(source/name,dest/name)
binary=root/'assets'/'studygadget.3dsx'
if binary.exists():shutil.copy2(binary,sd/'studygadget.3dsx')
smdh=root/'home-menu'/'studygadget.smdh'
if smdh.exists():shutil.copy2(smdh,sd/'studygadget.smdh')
(sd/'config.example.txt').write_text('192.168.1.100 8765 REPLACE_WITH_PHONE_PAIRING_TOKEN\n')
(sd/'config.hotspot.example.txt').write_text('auto 8765 REPLACE_WITH_PHONE_PAIRING_TOKEN\n')
cia=root/'release'/'cia'/'studygadget.cia'
cia_report=root/'release'/'cia'/'validation.json'
if cia.is_file() and cia_report.is_file():
    checked=json.loads(cia_report.read_text(encoding='utf8'))
    if not checked.get('passed') or checked.get('ciaSha256')!=hashlib.sha256(cia.read_bytes()).hexdigest():
        raise SystemExit('CIA has changed since verification; run tools/verify_home_cia.py')
    if checked.get('embeddedCgfxSha256')!=hashlib.sha256((root/'home-menu/banner.cgfx').read_bytes()).hexdigest():
        raise SystemExit('CIA contains an older banner; rebuild CIA before packaging')
    destination=root/'release/sd/cias';destination.mkdir(exist_ok=True)
    shutil.copy2(cia,destination/'studygadget.cia')
print('Character packaged:',skel, '48 motions,',header[4]//3,'triangles')
