"""Production C SHA/installer/streaming transport exercised on private test files.
Windows adapters simulate network I/O; no actual 3DS hardware is emulated.
"""
from pathlib import Path
import hashlib,json,os,struct,subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[1]
TOOLS=ROOT/'diagnostics/network-host/update-tools.exe'
CLIENT=ROOT/'diagnostics/network-host/network-client.exe'
RESULTS=[]

def fixture(seed=0):
    data=bytearray(120);struct.pack_into('<4sHH6I',data,0,b'3DSX',32,8,0,0,64,0,0,0)
    data[56:73]=b'STUDYGADGET_APP_V1';data[-1]=seed;return bytes(data)
class UpdateTests(unittest.TestCase):
    def setUp(self):
        self.root=Path(tempfile.mkdtemp(prefix='sg-ota-',dir=ROOT/'diagnostics/network-host'))
    def tearDown(self):
        # Retain private fixtures as diagnostics, including any backup used by a failing case.
        pass
    def run_tool(self,*args):
        result=subprocess.run([str(TOOLS),*map(str,args)],capture_output=True,text=True,timeout=10)
        self.assertEqual(result.returncode,0,result.stderr);return result.stdout.strip()
    def test_sha_vectors_and_padding_boundaries(self):
        for n in (0,1,3,55,56,63,64,65,127,128,4095,4096,4097,1000000):
            data=b'abc' if n==3 else b'a'*n;p=self.root/'hash.bin';p.write_bytes(data)
            self.assertEqual(self.run_tool('sha',p),hashlib.sha256(data).hexdigest())
    def test_validate_real_3ds_build(self):
        p=ROOT/'assets/studygadget.3dsx';self.assertEqual(self.run_tool('validate',p,p.stat().st_size,hashlib.sha256(p.read_bytes()).hexdigest()),'1')
    def test_install_keeps_launchable_backup(self):
        current=self.root/'studygadget.3dsx';new=self.root/'incoming';current.write_bytes(fixture(1));new.write_bytes(fixture(2))
        state=self.run_tool('install',current,new,new.stat().st_size,hashlib.sha256(new.read_bytes()).hexdigest())
        self.assertEqual(state,'4');self.assertEqual(current.read_bytes(),fixture(2));self.assertEqual((self.root/'studygadget.backup.3dsx').read_bytes(),fixture(1))
    def test_bad_hash_does_not_replace_current(self):
        current=self.root/'studygadget.3dsx';new=self.root/'incoming';current.write_bytes(fixture(1));new.write_bytes(fixture(2))
        self.assertEqual(self.run_tool('install',current,new,new.stat().st_size,'0'*64),'6');self.assertEqual(current.read_bytes(),fixture(1))
    def test_invalid_executable_does_not_replace_current(self):
        current=self.root/'studygadget.3dsx';new=self.root/'incoming';current.write_bytes(fixture(1));new.write_bytes(b'wrong application'*10)
        self.assertEqual(self.run_tool('install',current,new,new.stat().st_size,hashlib.sha256(new.read_bytes()).hexdigest()),'6');self.assertEqual(current.read_bytes(),fixture(1))
    def test_failed_backup_keeps_current(self):
        current=self.root/'studygadget.3dsx';new=self.root/'incoming';current.write_bytes(fixture(1));new.write_bytes(fixture(2))
        backup=self.root/'studygadget.backup.3dsx';backup.mkdir();(backup/'keep').write_text('nonempty directory')
        self.assertEqual(self.run_tool('install',current,new,new.stat().st_size,hashlib.sha256(new.read_bytes()).hexdigest()),'6');self.assertEqual(current.read_bytes(),fixture(1))
    def test_bad_and_oversized_manifests(self):
        current=self.root/'studygadget.3dsx';current.write_bytes(fixture(1))
        for manifest in ('SGAPP 1 16777217 '+'0'*64,'SGAPP 2 120 '+'0'*64,'SGAPP 1 120 '+'0'*64+' extra','bad'):
            self.assertEqual(self.run_tool('manifest',current,manifest),'6')
    def download(self,body,expected=None,truncate=False):
        expected=body if expected is None else expected
        wire=f'HTTP/1.1 200 OK\r\nContent-Length: {len(body)}\r\n\r\n'.encode()+(body[:-10] if truncate else body)
        response=self.root/'response';response.write_bytes(wire);dest=self.root/'download.next'
        environment=dict(os.environ,TEST_RESPONSE_FILE=str(response),TEST_SCENARIO='normal',TEST_DESTINATION=str(dest),TEST_SIZE=str(len(body)),TEST_SHA=hashlib.sha256(expected).hexdigest())
        result=subprocess.run([str(CLIENT),'8765','download'],capture_output=True,text=True,timeout=10,env=environment)
        self.assertEqual(result.returncode,0,result.stderr);return json.loads(result.stdout),dest
    def test_large_streaming_download(self):
        data=bytes(range(256))*1024;r,dest=self.download(data);self.assertEqual(r['code'],200);self.assertEqual(dest.read_bytes(),data)
    def test_corrupted_download_removed(self):
        r,dest=self.download(b'x'*5000,b'y'*5000);self.assertEqual(r['code'],-1);self.assertFalse(dest.exists())
    def test_truncated_download_removed(self):
        r,dest=self.download(b'x'*5000,truncate=True);self.assertEqual(r['code'],-1);self.assertFalse(dest.exists())

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(UpdateTests))
    (ROOT/'diagnostics/app-update-tests.json').write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'hardwareVerified':False},indent=2))
    raise SystemExit(not result.wasSuccessful())
