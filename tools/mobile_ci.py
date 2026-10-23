"""Helpers for phone-triggered GitHub Actions. Never package a signing key."""
import argparse,base64,binascii,hashlib,json,os,re,shutil,subprocess,sys
from pathlib import Path
from package_mobile_update import package
ROOT=Path(__file__).resolve().parents[1]
TARGETS=('3ds','android','both')
def plan(target,version_code,version_name,run_number,commit):
    if target not in TARGETS:raise ValueError('target must be 3ds, android or both')
    if not str(run_number).isdigit() or int(run_number)<1:raise ValueError('Invalid run number')
    code=int(version_code) if str(version_code).isdigit() else 100000+int(run_number) if not version_code else 0
    if not 7<=code<=2100000000:raise ValueError('version_code must be an integer from 7 to 2100000000')
    name=version_name or 'ci-'+str(run_number)+'-'+(commit[:7] if re.fullmatch('[0-9a-fA-F]{7,64}',commit or '') else 'local')
    if len(name)>80 or any(ord(c)<32 or ord(c)==127 for c in name):raise ValueError('Version name must be one line, at most 80 characters')
    return {'target':target,'version_code':str(code),'version_name':name}
def write_values(path,values):
    with Path(path).open('a',encoding='utf-8',newline='\n') as stream:
        for key,value in values.items():stream.write(key+'='+value+'\n')
def decode_signing(value):
    if not value:raise ValueError('Set ANDROID_SIGNING_KEYSTORE_BASE64 in GitHub Actions secrets. 3DS-only builds need no key.')
    try:data=base64.b64decode(value,validate=True)
    except (ValueError,binascii.Error):raise ValueError('ANDROID_SIGNING_KEYSTORE_BASE64 is not valid Base64') from None
    if not 100<=len(data)<=65536:raise ValueError('Signing keystore size is invalid')
    return data
def restore_signing():
    data=decode_signing(os.environ.get('ANDROID_SIGNING_KEYSTORE_BASE64',''))
    destination=Path(os.environ['RUNNER_TEMP']).resolve()/'studygadget-signing'/'debug.keystore'
    destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(data);destination.chmod(0o600)
    try:
        result=subprocess.run(['keytool','-exportcert','-keystore',str(destination),'-alias','androiddebugkey','-storepass','android','-keypass','android'],capture_output=True)
        if result.returncode:raise ValueError('Use the original debug.keystore (androiddebugkey / android)')
        fingerprint=hashlib.sha256(result.stdout).hexdigest()
        expected=(ROOT/'ci/android-signing-certificate.sha256').read_text(encoding='ascii').strip().lower()
        if not re.fullmatch('[0-9a-f]{64}',expected) or fingerprint!=expected:raise ValueError('Signing certificate differs from the existing app; use the original key.')
        write_values(os.environ['GITHUB_ENV'],{'STUDYGADGET_SIGNING_KEYSTORE':str(destination)})
        print('Original Android signing certificate verified: '+fingerprint)
    except BaseException:destination.unlink(missing_ok=True);raise
def bundle(target,version,incoming,output):
    if target not in TARGETS:raise ValueError('Invalid target')
    incoming,output=Path(incoming),Path(output)
    apk=incoming/'android'/'app-debug.apk' if target in ('android','both') else None
    three=incoming/'3ds'/'studygadget.3dsx' if target in ('3ds','both') else None
    for p in (apk,three):
        if p is not None and not p.is_file():raise ValueError('Selected build did not produce '+str(p))
    output.mkdir(parents=True,exist_ok=True)
    manifest=package(apk,three,output/'StudyGadget-update.sgupdate',version)
    checksums=[]
    for p in (apk,three):
        if p is not None:
            dest=output/('StudyCompanion.apk' if p==apk else 'studygadget.3dsx')
            shutil.copy2(p,dest);checksums.append(hashlib.sha256(dest.read_bytes()).hexdigest()+'  '+dest.name)
    checksums.append(hashlib.sha256((output/'StudyGadget-update.sgupdate').read_bytes()).hexdigest()+'  StudyGadget-update.sgupdate')
    (output/'SHA256SUMS.txt').write_text('\n'.join(checksums)+'\n',encoding='ascii')
    (output/'build-info.json').write_text(json.dumps({'target':target,'version':version,'commit':os.environ.get('GITHUB_SHA','local'),'run_id':os.environ.get('GITHUB_RUN_ID','local'),'files':manifest['files'],'hardwareVerified':False},ensure_ascii=False,indent=2),encoding='utf-8')
    return manifest
def main():
    parser=argparse.ArgumentParser();commands=parser.add_subparsers(dest='command',required=True)
    p=commands.add_parser('plan');p.add_argument('--target',default=os.environ.get('CI_TARGET','3ds'));p.add_argument('--version-code',default=os.environ.get('CI_VERSION_CODE',''));p.add_argument('--version-name',default=os.environ.get('CI_VERSION_NAME',''))
    commands.add_parser('restore-signing')
    p=commands.add_parser('bundle');p.add_argument('--target',required=True);p.add_argument('--version',required=True);p.add_argument('--incoming',default='incoming');p.add_argument('--output',default='outgoing')
    args=parser.parse_args()
    try:
        if args.command=='plan':
            result=plan(args.target,args.version_code,args.version_name,os.environ.get('GITHUB_RUN_NUMBER','1'),os.environ.get('GITHUB_SHA','local'))
            if os.environ.get('GITHUB_OUTPUT'):write_values(os.environ['GITHUB_OUTPUT'],result)
            print(json.dumps(result,ensure_ascii=True))
        elif args.command=='restore-signing':restore_signing()
        else:print(json.dumps(bundle(args.target,args.version,args.incoming,args.output),ensure_ascii=True))
    except (ValueError,KeyError,FileNotFoundError) as e:print(str(e),file=sys.stderr);return 1
    return 0
if __name__=='__main__':raise SystemExit(main())

