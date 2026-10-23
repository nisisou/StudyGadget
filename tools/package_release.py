"""Package only deliverables/source; exclude build caches, pairing data and signing keys."""
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
import hashlib,json,shutil
root=Path(__file__).resolve().parents[1]
release=root/'release';release.mkdir(exist_ok=True)
required=[release/'StudyCompanion-character-debug.apk',root/'character'/'character-motions.blend',release/'android-tests.txt']
for p in required:
    if not p.is_file():raise SystemExit('Missing verified deliverable: '+str(p))
shutil.copy2(root/'README.md',release/'README.md')
artifact=release/'StudyGadget-character-package.zip'
files={root/'README.md',root/'main.c',root/'Makefile'}
if (root/'.gitignore').exists():files.add(root/'.gitignore')
for name in ('character-renamed.blend','character-motions.blend','model.sgch','motions.sgmo','manifest.json','bone-map.json','preview.png'):
    files.add(root/'character'/name)
for p in (root/'tools').iterdir():
    if p.is_file() and p.suffix in ('.py','.ps1','.gradle'):files.add(p)
for p in (root/'home-menu').iterdir():
    if p.is_file() and p.suffix in ('.md','.json','.png','.gif','.blend','.gltf','.glb','.bin','.wav','.rsf','.smdh','.cgfx'):files.add(p)
for name in ('main.c','Makefile','character.c','character.h','character_sync.c','character_sync.h','network_peer.c','network_peer.h','network_client.c','network_client.h','app_update.c','app_update.h','update_sha256.c','update_sha256.h','character.v.pica','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','README.md'):
    files.add(root/'assets'/name)
for sub in ('.github/workflows','tools/network-tests','assets/app/src','assets/app/schemas','assets/gradle','legacy','release/sd','release/cia','diagnostics/build-config-before-server-fix'):
    files.update(p for p in (root/sub).rglob('*') if p.is_file())
for name in ('build.gradle.kts','proguard-rules.pro'):files.add(root/'assets'/'app'/name)
files.update(required)
if (release/'validation.json').exists():files.add(release/'validation.json')
if (root/'diagnostics'/'network-tests.json').exists():files.add(root/'diagnostics'/'network-tests.json')
with ZipFile(artifact,'w',ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(files):
        if not p.is_file():raise SystemExit('Missing source: '+str(p))
        z.write(p,p.relative_to(root).as_posix())
with ZipFile(artifact,'r') as z:
    if z.testzip() is not None:raise SystemExit('Archive integrity check failed')
print(json.dumps({'archive':str(artifact),'files':len(files),'bytes':artifact.stat().st_size,'sha256':hashlib.sha256(artifact.read_bytes()).hexdigest()}))
