"""Meaningful checks for target selection, signing input, and importable bundles."""
import base64,hashlib,json,struct,tempfile,unittest
from pathlib import Path
from zipfile import ZipFile
from mobile_ci import plan,decode_signing,bundle
class MobileCITests(unittest.TestCase):
 def test_automatic_versions_are_increasing(self):
  a=plan('both','','',42,'a'*40);b=plan('both','','',43,'b'*40)
  self.assertLess(int(a['version_code']),int(b['version_code']))
  self.assertEqual(a['version_name'],'ci-42-aaaaaaa')
 def test_manual_version_and_unicode_name(self):
  self.assertEqual(plan('android','200001','スマホ版',1,'')['version_name'],'スマホ版')
 def test_rejects_invalid_target_version_and_multiline_output(self):
  for args in [('bad','','',1,''),('3ds','abc','',1,''),('both','2100000001','',1,''),('both','','first\ninjected=value',1,''),('3ds','','',0,'')]:
   with self.assertRaises(ValueError):plan(*args)
 def test_signing_secret_is_required_and_bounded(self):
  for value in ('','not base64',base64.b64encode(b'x').decode(),base64.b64encode(b'x'*65537).decode()):
   with self.assertRaises(ValueError):decode_signing(value)
  self.assertEqual(decode_signing(base64.b64encode(b'x'*100).decode()),b'x'*100)
 def fixtures(self,root):
  android=root/'android';android.mkdir()
  with ZipFile(android/'app-debug.apk','w') as z:z.writestr('AndroidManifest.xml',b'test');z.writestr('classes.dex',b'test')
  three=root/'3ds';three.mkdir();data=bytearray(120)
  struct.pack_into('<4sHH6I',data,0,b'3DSX',32,8,0,0,64,0,0,0)
  data[56:73]=b'STUDYGADGET_APP_V1';(three/'studygadget.3dsx').write_bytes(data)
 def check_target(self,target,expected):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);incoming=root/'in';incoming.mkdir();self.fixtures(incoming);out=root/'out'
   result=bundle(target,'test',incoming,out)
   self.assertEqual({x['target'] for x in result['files']},expected)
   with ZipFile(out/'StudyGadget-update.sgupdate') as z:
    for f in result['files']:self.assertEqual(hashlib.sha256(z.read(f['path'])).hexdigest(),f['sha256'])
   self.assertNotIn('keystore',(out/'build-info.json').read_text())
   return out
 def test_three_ds_only_bundle(self):self.check_target('3ds',{'3ds'})
 def test_android_only_bundle(self):self.check_target('android',{'android'})
 def test_combined_bundle(self):self.check_target('both',{'3ds','android'})
 def test_missing_selected_binary_fails(self):
  with tempfile.TemporaryDirectory() as directory:
   with self.assertRaises(ValueError):bundle('android','test',Path(directory)/'missing',Path(directory)/'out')
 def test_corrupt_executable_fails(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);self.fixtures(root);(root/'3ds/studygadget.3dsx').write_bytes(b'wrong')
   with self.assertRaises(ValueError):bundle('3ds','test',root,root/'out')

 def test_three_ds_bundle_includes_cia_when_present(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);incoming=root/'in';incoming.mkdir();self.fixtures(incoming)
   cia=bytearray(0x2040+0x200)
   struct.pack_into('<I',cia,0,0x2020)
   struct.pack_into('<Q',cia,24,0x200)
   cia[0x2040+0x100:0x2040+0x104]=b'NCCH'
   struct.pack_into('<Q',cia,0x2040+0x118,0x000400000F764200)
   (incoming/'3ds/studygadget.cia').write_bytes(cia)
   out=root/'out'
   result=bundle('3ds','test-cia',incoming,out)
   self.assertEqual({x['target'] for x in result['files']},{'3ds','cia'})
   self.assertTrue((out/'studygadget.cia').is_file())
   with ZipFile(out/'StudyGadget-update.sgupdate') as z:
    self.assertIn('studygadget.cia',z.namelist())

if __name__=='__main__':unittest.main(verbosity=2)

