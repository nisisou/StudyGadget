"""Run real UI/session C functions in an isolated host DLL. No production I/O."""
from pathlib import Path
import argparse, ctypes, hashlib, json, os, re, subprocess, sys
ROOT=Path(__file__).resolve().parents[1]
SUPPORT=ROOT/'tools/vscode-preview'
CACHE=ROOT/'diagnostics/ui-preview'

def function(source,name):
    match=re.search(r'^static\s+[^\n]*\b'+re.escape(name)+r'\([^\n]*\)\s*\{',source,re.M)
    if not match:raise ValueError(f'Preview cannot find {name} in assets/main.c')
    start=source.index('{',match.start());depth=1
    token=re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|[{}]')
    for m in token.finditer(source,start+1):
        if m.group()=='{':depth+=1
        elif m.group()=='}':depth-=1
        if depth==0:return source[match.start():m.end()]
    raise ValueError(f'Unclosed function {name}')

def generate():
    source=(ROOT/'assets/main.c').read_text(encoding='utf-8-sig')
    prefix=source[source.index('#define DIR_'):source.index('/* ---------- network ---------- */')]
    prefix=re.sub(r'^#include[^\n]*\n','',prefix,flags=re.M)
    prefix=re.sub(r'^.*__attribute__.*$','',prefix,flags=re.M)
    sessions=source[source.index('static void set_target('):source.index('/* ---------- drawing ---------- */')]
    drawings=source[source.index('/* ---------- drawing ---------- */'):source.index('static int graphics_failure(')]
    stubs='''
static void save(long duration){(void)duration;pending_count++;}
static void queue_event(const char *path,const char *body){(void)path;(void)body;}
static void request_sync(bool logs){(void)logs;snprintf(network_status,sizeof network_status,"NET: simulated sync complete");}
static const char *dev_update_status(void){return "preview: updates simulated";}
'''
    line=source[:source.index('/* ---------- drawing ---------- */')].count('\n')+1
    return '#include "host.h"\n'+prefix+'\n'+function(source,'parse_subjects')+'\n'+stubs+'\n'+sessions+'\n#line '+str(line)+' "assets/main.c"\n'+drawings+'\n'+(SUPPORT/'footer.c').read_text(encoding='utf-8-sig')

def find_vcvars():
    configured=os.environ.get('STUDYGADGET_VCVARS')
    if configured:return Path(configured)
    candidates=list(Path(r'C:\Program Files\Microsoft Visual Studio').glob('**/VC/Auxiliary/Build/vcvars64.bat'))
    if not candidates:raise RuntimeError('Install Visual Studio C++ Build Tools or set STUDYGADGET_VCVARS.')
    return sorted(candidates,reverse=True)[0]

def compile_preview():
    CACHE.mkdir(parents=True,exist_ok=True)
    code=generate();dependencies=''.join((ROOT/'assets'/name).read_text(encoding='utf-8-sig') for name in ['character.h','character_sync.h','network_client.h','app_update.h']);digest=hashlib.sha256((code+(SUPPORT/'host.h').read_text(encoding='utf-8-sig')+dependencies).encode()).hexdigest()[:16]
    dll=CACHE/f'ui-{digest}.dll'
    if not dll.exists():
        source=CACHE/f'ui-{digest}.c';source.write_text(code,encoding='utf-8')
        batch=CACHE/'compile.cmd'
        batch.write_text('@echo off\ncall "'+str(find_vcvars())+'" >nul\ncl /nologo /std:c11 /utf-8 /LD /W3 /D_CRT_SECURE_NO_WARNINGS /I"'+str(SUPPORT.relative_to(ROOT))+'" "'+str(source.relative_to(ROOT))+'" /Fo"'+str((CACHE/f'ui-{digest}.obj').relative_to(ROOT))+'" /Fe"'+str(dll.relative_to(ROOT))+'" /link /IMPLIB:"'+str((CACHE/f'ui-{digest}.lib').relative_to(ROOT))+'"\n',encoding='utf-8')
        result=subprocess.run(['cmd.exe','/d','/c',str(batch.relative_to(ROOT))],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        if result.returncode:raise RuntimeError(result.stdout.decode('mbcs',errors='replace'))
    library=ctypes.CDLL(str(dll));library.sg_frame.restype=ctypes.c_char_p
    library.sg_frame.argtypes=[ctypes.c_uint64,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int]
    library.sg_reset();return library,digest

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--stdio',action='store_true');parser.add_argument('--check',action='store_true');args=parser.parse_args()
    try:lib,digest=compile_preview()
    except Exception as e:
        print(json.dumps({'error':str(e)},ensure_ascii=True),flush=True);return 1
    if args.check:
        data=json.loads(lib.sg_frame(100000,-1,-1,0,0));print(json.dumps({'ok':True,'source':digest,'operations':len(data['ops'])}));return 0
    print(json.dumps({'ready':True,'source':digest}),flush=True)
    for line in sys.stdin:
        try:
            event=json.loads(line)
            if event.get('reset'):lib.sg_reset()
            result=lib.sg_frame(int(event.get('now',100000)),int(event.get('x',-1)),int(event.get('y',-1)),int(event.get('key',0)),int(event.get('connection',0)))
            print(result.decode('utf-8'),flush=True)
        except Exception as e:print(json.dumps({'error':str(e)},ensure_ascii=True),flush=True)
    return 0

if __name__=='__main__':raise SystemExit(main())

