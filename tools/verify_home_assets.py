"""Verify generated glTF/GLB, loop endpoints and actual embedded SMDH."""
from pathlib import Path
import json, struct
import numpy as np

root = Path(__file__).resolve().parents[1]
folder = root / 'home-menu'
gltf = json.loads((folder / 'banner.gltf').read_text(encoding='utf8'))
binary = (folder / 'banner.bin').read_bytes()
assert not gltf.get('skins')
assert len(gltf['animations']) == 1
assert all('skin' not in n for n in gltf['nodes'])
for mesh in gltf['meshes']:
    for p in mesh['primitives']:
        assert not any(k.startswith(('JOINTS', 'WEIGHTS')) for k in p['attributes'])
for animation in gltf['animations']:
    for channel in animation['channels']:
        assert channel['target']['path'] in ('translation', 'rotation', 'scale')
        assert channel['target']['node'] == 0
    for sampler in animation['samplers']:
        a = gltf['accessors'][sampler['output']]; v = gltf['bufferViews'][a['bufferView']]
        components = {'VEC3': 3, 'VEC4': 4}[a['type']]
        values = np.frombuffer(binary, dtype='<f4', offset=v['byteOffset'], count=a['count']*components).reshape(-1, components)
        assert np.isfinite(values).all()
        assert np.allclose(values[0], values[-1], atol=1e-6), 'Loop endpoints differ'
glb = (folder / 'banner.glb').read_bytes()
magic, version, size = struct.unpack_from('<4sII', glb)
assert magic == b'glTF' and version == 2 and size == len(glb)
n, kind = struct.unpack_from('<I4s', glb, 12)
assert kind == b'JSON'
document = json.loads(glb[20:20+n])
length, kind = struct.unpack_from('<I4s', glb, 20+n)
assert kind == b'BIN\0' and 28+n+length == len(glb)
assert len(document['bufferViews']) == len(gltf['bufferViews']) + 1
for view in document['bufferViews']:
    assert view.get('byteOffset', 0) + view['byteLength'] <= document['buffers'][0]['byteLength']
smdh = (folder / 'studygadget.smdh').read_bytes()
assert smdh[:4] == b'SMDH' and len(smdh) == 0x36c0
assert (root / 'assets/studygadget.3dsx').read_bytes().find(smdh) >= 0, 'SMDH not embedded'
print('PASS: rigid glTF/GLB, finite closed loop, buffer bounds, embedded SMDH. CGFX/CIA checks are in separate tools; hardware not tested.')
