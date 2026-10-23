"""Ship the initial updater, ready-to-import bundle and rebuildable sources together."""
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
import hashlib,json,shutil,re
from package_mobile_update import package
ROOT=Path(__file__).resolve().parents[1]
RELEASE=ROOT/'release'
apk=RELEASE/'StudyCompanion-character-debug.apk'
exe=ROOT/'assets/studygadget.3dsx'
guide=RELEASE/'mobile-update-guide.md'
for p in (apk,exe,guide):
    if not p.is_file():raise SystemExit('Missing deliverable: '+str(p))
bundle=RELEASE/'StudyGadget-update.sgupdate'
manifest=package(apk,exe,bundle,'1.4-mobile-update / 3DS 2.2')
checks={name:json.loads((ROOT/'diagnostics'/name).read_text()) for name in ('network-tests.json','app-update-tests.json')}
for report in checks.values():
    if report['failures'] or report.get('errors',0):raise SystemExit('Updater tests did not pass')
tests=(RELEASE/'android-tests.txt').read_text()
m=re.search(r'JUnit: (\d+) tests, (\d+) failures',tests)
if not m or int(m.group(2)):raise SystemExit('Android tests did not pass')
validation={'androidBuild':'passed','threeDSBuild':'passed','androidVersionCode':6,'androidTests':int(m.group(1)),
            'transportTests':checks['network-tests.json']['tests'],'updaterTests':checks['app-update-tests.json']['tests'],
            'hardwareVerified':False,'cloudWorkflowExecuted':False,'initialInstallRequired':True,'threeDSUpdateTarget':'HBL 3dsx',
            'apkSignatureVerified':True,'apkSignerSha256':'dc8cbd9acfba334e18b31dee94806ee578f28816900e18f8202f9412cf1d2197',
            'files':manifest['files']}
validation_file=RELEASE/'mobile-update-validation.json';validation_file.write_text(json.dumps(validation,indent=2),encoding='utf-8')
# Keep the canonical deployment folder current without regenerating character data.
sd=RELEASE/'sd/3ds/studygadget';sd.mkdir(parents=True,exist_ok=True)
shutil.copy2(exe,sd/'studygadget.3dsx')
shutil.copy2(ROOT/'home-menu/studygadget.smdh',sd/'studygadget.smdh')
items={apk:'StudyCompanion.apk',bundle:bundle.name,guide:'README.md',validation_file:'validation.json'}
for p in sd.iterdir():
    if p.is_file() and p.name in ('studygadget.3dsx','studygadget.smdh','model.sgch','motions.sgmo','manifest.json','config.example.txt','config.hotspot.example.txt'):items[p]='sd/3ds/studygadget/'+p.name
for sub in ('assets/app/src','assets/app/schemas','assets/gradle','.github/workflows','tools/network-tests'):
    for p in (ROOT/sub).rglob('*'):
        if p.is_file():items[p]='source/'+p.relative_to(ROOT).as_posix()
for p in (ROOT/'assets').iterdir():
    if p.is_file() and (p.suffix in ('.c','.h','.pica') or p.name in ('Makefile','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat')):items[p]='source/assets/'+p.name
for p in (ROOT/'tools').iterdir():
    if p.is_file() and p.suffix in ('.py','.ps1','.gradle'):items[p]='source/tools/'+p.name
for name in ('main.c','Makefile','README.md','.gitignore'):items[ROOT/name]='source/'+name
for name in ('build.gradle.kts','proguard-rules.pro'):items[ROOT/'assets/app'/name]='source/assets/app/'+name
for name in ('model.sgch','motions.sgmo','manifest.json'):items[ROOT/'character'/name]='source/character/'+name
for name in ('studygadget.smdh','icon.png','icon-small.png'):items[ROOT/'home-menu'/name]='source/home-menu/'+name
for name in ('network-tests.json','app-update-tests.json'):items[ROOT/'diagnostics'/name]='source/diagnostics/'+name
items[RELEASE/'android-tests.txt']='source/release/android-tests.txt'
items[guide]='README.md'
# A source project cannot include credentials or machine-specific SDK settings.
for path,name in list(items.items()):
    if path.suffix.lower() in ('.keystore','.jks') or path.name=='local.properties' or any(part in ('.gradle','.android-local','__pycache__') for part in path.parts):raise SystemExit('Private/cache file selected: '+name)
archive=RELEASE/'StudyGadget-mobile-update.zip'
with ZipFile(archive,'w',ZIP_DEFLATED,compresslevel=6) as z:
    for path,name in sorted(items.items()):z.write(path,name)
    z.write(guide,"source/release/mobile-update-guide.md")
with ZipFile(archive) as z:
    if z.testzip() is not None:raise SystemExit('ZIP integrity failed')
    if z.read('sd/3ds/studygadget/studygadget.3dsx')!=exe.read_bytes():raise SystemExit('3DSX mismatch')
    if z.read('StudyCompanion.apk')!=apk.read_bytes():raise SystemExit('APK mismatch')
    if len(z.namelist())!=len(set(z.namelist())):raise SystemExit('Duplicate ZIP paths')
print(json.dumps({'archive':str(archive),'bytes':archive.stat().st_size,'files':len(items),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'validation':validation}))
