"""Create a clean source import, including the hidden Actions directory."""
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
import hashlib,json
ROOT=Path(__file__).resolve().parents[1]
def collect():
    paths=set()
    for name in ('README.md','Makefile','main.c','.gitignore','.gitattributes','Open-StudyGadget.cmd'):
        if (ROOT/name).is_file():paths.add(ROOT/name)
    for directory in ('.github','.vscode','docs','ci','assets/app/src','assets/app/schemas','assets/gradle','tools/ci','tools/vscode-extension','tools/vscode-preview','tools/network-tests'):
        paths.update(p for p in (ROOT/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.name!='scheduler-host.h')
    for p in (ROOT/'assets').iterdir():
        if p.is_file() and (p.suffix in ('.c','.h','.pica','.kts') or p.name in ('Makefile','gradlew','gradlew.bat','gradle.properties','README.md')):
            if p.name not in ('character_shader.c','character_shader.h'):paths.add(p)
    for p in (ROOT/'tools').iterdir():
        if p.is_file() and p.suffix in ('.py','.ps1','.js','.gradle'):paths.add(p)
    for name in ('model.sgch','motions.sgmo','manifest.json','bone-map.json'):paths.add(ROOT/'character'/name)
    for name in ('build.gradle.kts','proguard-rules.pro'):paths.add(ROOT/'assets/app'/name)
    for p in paths:
        if p.name in ('config.txt','local.properties') or p.suffix in ('.jks','.keystore','.apk','.3dsx','.elf'):raise ValueError('Unexpected private/generated file: '+str(p))
        if not p.is_file():raise ValueError('Missing build input: '+str(p))
    return paths
def main():
    output=ROOT/'release/StudyGadget-mobile-ci-source.zip';output.parent.mkdir(exist_ok=True)
    paths=collect()
    with ZipFile(output,'w',ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(paths):z.write(p,p.relative_to(ROOT).as_posix())
    with ZipFile(output) as z:
        assert z.testzip() is None
        assert '.github/workflows/mobile-update.yml' in z.namelist()
        assert 'ci/android-signing-certificate.sha256' in z.namelist()
        assert 'assets/gradle/wrapper/gradle-wrapper.jar' in z.namelist()
    print(json.dumps({'file':str(output),'files':len(paths),'bytes':output.stat().st_size,'sha256':hashlib.sha256(output.read_bytes()).hexdigest()},ensure_ascii=True))
if __name__=='__main__':main()

