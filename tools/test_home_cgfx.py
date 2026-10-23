"""Regression checks that reject damaged or unsafe serialized banners."""
from pathlib import Path
import struct, unittest
from cgfx_inspect import Reader, inspect_bytes, compare_source
ROOT=Path(__file__).resolve().parents[1]

class CgfxTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data=(ROOT/'home-menu/banner.cgfx').read_bytes()
        cls.report=inspect_bytes(cls.data)
        if not cls.report['passed']: raise RuntimeError(cls.report['errors'])
    def mutate_u32(self,offset,value):
        b=bytearray(self.data); struct.pack_into('<I',b,offset,value); return bytes(b)
    def assert_rejected(self,data,reason):
        report=inspect_bytes(data)
        self.assertFalse(report['passed'])
        self.assertTrue(any(reason in x for x in report['errors']),report['errors'])
    def test_current_file_and_source_sidedness(self):
        result=compare_source(inspect_bytes(self.data),self.data,ROOT/'home-menu/banner.glb')
        self.assertTrue(result['passed'],result['errors'])
    def test_missing_mesh_binding_rejected(self):
        mesh=self.report['models'][0]['meshes'][0]['offset']
        self.assert_rejected(self.mutate_u32(mesh+112,0),'Null pointer')
    def test_soft_skin_mode_rejected(self):
        r=Reader(self.data)
        model=r.dictionary(28)[0][1]; shape=r.list(model+196)[0]; ps=r.list(shape+44)[0]
        self.assert_rejected(self.mutate_u32(ps+8,2),'Skinning mode')
    def test_bone_weight_stream_rejected(self):
        r=Reader(self.data)
        model=r.dictionary(28)[0][1]; shape=r.list(model+196)[0]; stream=r.list(shape+56)[0]
        self.assert_rejected(self.mutate_u32(stream+4,8),'BoneIndex/BoneWeight')
    def test_wrong_animation_primitive_rejected(self):
        offset=self.report['animations'][0]['members'][0]['offset']
        self.assert_rejected(self.mutate_u32(offset+16,8),'expected Transform')
    def test_out_of_file_dictionary_pointer_rejected(self):
        self.assert_rejected(self.mutate_u32(32,0x7fffffff),'Out-of-file')
    def test_truncated_file_rejected(self):
        self.assert_rejected(self.data[:-1],'file length')
    def test_oversize_file_rejected(self):
        b=bytearray(self.data+b'\0'*(524289-len(self.data)))
        struct.pack_into('<I',b,12,len(b))
        self.assert_rejected(bytes(b),'512 KiB')
    def test_missing_reverse_winding_rejected(self):
        r=Reader(self.data); shape=self.report['models'][0]['shapes'][0]
        stream=shape['indexStreamOffsets'][0]; start=r.pointer(stream+12)
        width=2 if r.u32(stream)==0x1403 else 1
        b=bytearray(self.data)
        # Corrupt only the reverse half, preserving all structural bounds.
        count=r.u32(stream+8)//width
        b[start+(count//2)*width:start+(count//2+3)*width]=b[start:start+3*width]
        report=inspect_bytes(bytes(b)); self.assertTrue(report['passed'],report['errors'])
        compare_source(report,bytes(b),ROOT/'home-menu/banner.glb')
        self.assertFalse(report['passed'])
        self.assertIn('Back-face triangle winding is not reversed',report['errors'])

if __name__=='__main__': unittest.main(verbosity=2)

