import unittest,struct,zlib,json,math,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from motion_gateway import validate
from preview_character import load,pose
import numpy as np

class CharacterTests(unittest.TestCase):
    def test_export_layout_checksum_and_batch_winding(self):
        data=(ROOT/'character'/'model.sgch').read_bytes()
        magic,ver,skel,nb,nv,tex,batches,crc,size=struct.unpack_from('<4s8I',data)
        self.assertEqual((magic,ver),(b'SGCH',1));self.assertEqual(size,len(data)-36)
        self.assertEqual(size,nb*164+batches*12+nv*56+tex*tex*4)
        self.assertEqual(zlib.crc32(data[36:]),crc)
        manifest=json.loads((ROOT/'character'/'manifest.json').read_text())
        self.assertEqual(manifest['crc'],crc);self.assertIn(1,[b[2] for b in manifest['batches']])
        broken=bytearray(data);broken[-20]^=1;self.assertNotEqual(zlib.crc32(broken[36:]),crc)
    def test_all_states_have_finite_noncollapsed_skinning(self):
        bones,batches,vs,atlas=load(ROOT/'character'/'model.sgch')
        self.assertEqual(len(bones),23)
        for i,(_,p,local,inv) in enumerate(bones):
            self.assertLess(p,i);self.assertTrue(np.all(np.isfinite(local)))
        self.assertTrue(np.allclose(vs['weight'].sum(axis=1),1,atol=.001))
        for state in range(6):
            for t in (0,1.5,7,30):
                matrices=pose(bones,state,t)
                self.assertTrue(np.isfinite(matrices).all())
                self.assertTrue(np.allclose(np.linalg.det(matrices[:,:3,:3]),1,atol=.001))
    def test_motion_wire_and_relay_rejection(self):
        lines=(ROOT/'character'/'motions.sgmo').read_text().splitlines()
        self.assertEqual(int(lines[0].split()[-1]),48)
        keys=['state','duration','speed','intensity','sway','head','arm','phase']
        recipes=[]
        for line in lines[1:]:
            vals=list(map(float,line.split()));vals[0]=int(vals[0]);recipes.append(dict(zip(keys,vals)))
        self.assertEqual(len(validate(recipes)),48)
        for mutation in ({'arm':2},{'speed':float('nan')},{'state':99},{'code':'anything'}):
            bad=[dict(r) for r in recipes];bad[0].update(mutation)
            with self.assertRaises(ValueError):validate(bad)
        with self.assertRaises(ValueError):validate([r for r in recipes if r['state']!=5])

if __name__=='__main__':unittest.main(verbosity=2)
