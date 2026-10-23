"""Native Windows 3DS build used by the VS Code button. No shell quoting needed."""
from pathlib import Path
import argparse,os,runpy,shutil,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]
def run(args,cwd):
    result=subprocess.run([str(a) for a in args],cwd=cwd)
    if result.returncode:raise RuntimeError('Build command failed ('+str(result.returncode)+'): '+str(args[0]))
def build(devkit):
    compiler=devkit/'devkitARM/bin/arm-none-eabi-gcc.exe'
    for tool in (compiler,devkit/'tools/bin/picasso.exe',devkit/'tools/bin/3dsxtool.exe',devkit/'tools/bin/smdhtool.exe'):
        if not tool.is_file():raise RuntimeError('Missing devkitPro tool: '+str(tool))
    sys.path.insert(0,str(ROOT/'tools'))
    runpy.run_path(str(ROOT/'tools/export_home_banner.py'),run_name='__main__')
    run([devkit/'tools/bin/smdhtool.exe','--create','Study Gadget','Character study timer','Study Gadget','icon.png','studygadget.smdh','icon-small.png'],ROOT/'home-menu')
    assets=ROOT/'assets'
    run([devkit/'tools/bin/picasso.exe','-o','character.shbin','character.v.pica'],assets)
    run([sys.executable,'../tools/embed_shader.py','character.shbin','character_shader.c','character_shader.h'],assets)
    flags=['-std=gnu11','-Wall','-Wextra','-O2','-mword-relocations','-ffunction-sections','-march=armv6k','-mtune=mpcore','-mfloat-abi=hard','-mtp=soft','-D__3DS__','-I'+str(devkit/'libctru/include')]
    sources=['main.c','character.c','character_sync.c','network_client.c','network_peer.c','app_update.c','update_sha256.c','dev_relaunch.c','character_shader.c']
    run([compiler,*flags,*sources,'-specs=3dsx.specs','-L'+str(devkit/'libctru/lib'),'-lcitro2d','-lcitro3d','-lctru','-lm','-o','studygadget.elf'],assets)
    run([devkit/'tools/bin/3dsxtool.exe','studygadget.elf','studygadget.3dsx','--smdh=../home-menu/studygadget.smdh'],assets)
    for name in ('studygadget.3dsx','studygadget.elf'):shutil.copy2(assets/name,ROOT/name)
    sd=ROOT/'release/sd/3ds/studygadget';sd.mkdir(parents=True,exist_ok=True)
    shutil.copy2(ROOT/'studygadget.3dsx',sd/'studygadget.3dsx')
    shutil.copy2(ROOT/'home-menu/studygadget.smdh',sd/'studygadget.smdh')
    print('3DS build complete: studygadget.3dsx',flush=True)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--devkit',type=Path,default=Path(os.environ.get('STUDYGADGET_DEVKITPRO',r'C:\devkitPro')));a=p.parse_args()
    try:build(a.devkit)
    except Exception as e:print(str(e),file=sys.stderr);raise SystemExit(1)
