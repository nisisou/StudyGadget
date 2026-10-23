"""Package this local VS Code workflow, excluding caches, credentials and keys."""
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
import hashlib,json
ROOT=Path(__file__).resolve().parents[1]
files=set()
def add(p):
    p=ROOT/p if isinstance(p,str) else p
    if p.is_file():files.add(p)
for name in ('README.md','Makefile','main.c','Open-StudyGadget.cmd','.gitignore','studygadget.3dsx'):add(name)
for directory in ('.vscode','docs','tools/vscode-extension','tools/vscode-preview','tools/network-tests','assets/app/src','assets/app/schemas','assets/gradle'):
    for p in (ROOT/directory).rglob('*'):
        if p.is_file() and '__pycache__' not in p.parts and p.name!='scheduler-host.h':add(p)
for p in (ROOT/'assets').iterdir():
    if p.is_file() and (p.suffix in ('.c','.h','.pica','.kts') or p.name in ('Makefile','gradle.properties','gradlew','gradlew.bat','README.md')):add(p)
for name in ('build.gradle.kts','proguard-rules.pro'):add('assets/app/'+name)
for p in (ROOT/'tools').iterdir():
    if p.is_file() and p.suffix in ('.py','.ps1','.gradle','.js'):add(p)
for name in ('model.sgch','motions.sgmo','manifest.json','bone-map.json'):add('character/'+name)
for name in ('icon.png','icon-small.png','studygadget.smdh','README.md'):add('home-menu/'+name)
for p in (ROOT/'release/sd').rglob('*'):
    if p.is_file() and p.name not in ('config.txt','sessions.jsonl','pending.jsonl','pending.upload','boot.log','boot.previous.log'):add(p)
for name in ('StudyCompanion-character-debug.apk','studygadget-tools-0.1.0.vsix'):add('release/'+name)
for name in ('ui-preview-tests.json','vscode-workflow-tests.json','app-update-tests.json'):add('diagnostics/'+name)
for p in files:
    if p.name in ('local.properties','debug.keystore','config.txt') or p.suffix in ('.jks','.keystore'):raise RuntimeError('Private file in package: '+str(p))
output=ROOT/'release/StudyGadget-vscode-workflow.zip'
with ZipFile(output,'w',ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(files):z.write(p,p.relative_to(ROOT).as_posix())
with ZipFile(output) as z:
    if z.testzip():raise RuntimeError('Archive failed integrity check')
print(json.dumps({'archive':str(output),'files':len(files),'bytes':output.stat().st_size,'sha256':hashlib.sha256(output.read_bytes()).hexdigest()},ensure_ascii=True))
