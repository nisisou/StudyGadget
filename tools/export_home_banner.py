"""Create HOME icon and a rigid, animated glTF from our actual SGCH model.

The banner deliberately has no JOINTS, WEIGHTS, skins or morphs. Its baked
character sways and floats as one mesh node; runtime character motion is separate.
Requires numpy/Pillow, no Blender plug-in. CGFX conversion remains a separate step.
"""
from pathlib import Path
from io import BytesIO
import json, math, struct, wave
import numpy as np
from PIL import Image, ImageDraw
from preview_character import load, pose, render, rotation

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'home-menu'

def export():
    OUT.mkdir(exist_ok=True)
    bones, batches, vs, atlas = load(ROOT / 'character/model.sgch')
    skin = pose(bones, 0, 0)
    points = np.c_[vs['p'], np.ones(len(vs))]
    points = np.einsum('nkij,nj,nk->ni', skin[vs['bone']], points, vs['weight'])[:, :3]
    center = (points.min(axis=0) + points.max(axis=0)) / 2
    points = (points - center) * (14 / np.ptp(points[:, 1]))
    texture = Image.fromarray(atlas).resize((128, 128), Image.Resampling.LANCZOS)
    texture.save(OUT / 'banner-atlas.png')
    doc = {'asset': {'version': '2.0', 'generator': 'StudyGadget rigid HOME banner'},
           'scene': 0, 'scenes': [{'nodes': [0]}],
           'nodes': [{'name': 'character', 'mesh': 0, 'translation': [0, 1, 0]}],
           'meshes': [{'name': 'character', 'primitives': []}],
           'materials': [], 'textures': [{'source': 0, 'sampler': 0}],
           'samplers': [{'magFilter': 9729, 'minFilter': 9729, 'wrapS': 33071, 'wrapT': 33071}],
           'images': [{'uri': 'banner-atlas.png'}], 'bufferViews': [], 'accessors': []}
    binary = bytearray()

    def view(data, target=None):
        binary.extend(b'\0' * (-len(binary) % 4))
        result = {'buffer': 0, 'byteOffset': len(binary), 'byteLength': len(data)}
        if target: result['target'] = target
        doc['bufferViews'].append(result); binary.extend(data)
        return len(doc['bufferViews']) - 1

    def accessor(array, kind, component=5126, target=None):
        a = np.asarray(array, dtype='<u2' if component == 5123 else '<f4')
        result = {'bufferView': view(a.tobytes(), target), 'componentType': component,
                  'count': len(a), 'type': kind}
        if kind != 'SCALAR' or component == 5126:
            result['min'] = np.atleast_1d(a.min(axis=0)).tolist()
            result['max'] = np.atleast_1d(a.max(axis=0)).tolist()
        doc['accessors'].append(result)
        return len(doc['accessors']) - 1

    for start, count, mode in batches:
        # Preserve material culling, including inverted-hull front culling.
        p, uv = points[start:start + count], vs['uv'][start:start + count]
        records = np.c_[p, uv].astype('<f4')
        unique, inverse = np.unique(records, axis=0, return_inverse=True)
        indices = inverse.reshape(-1, 3).copy()
        if mode == 2: indices = indices[:, [0, 2, 1]]
        triangles = unique[indices, :3]
        face_normals = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
        normals = np.zeros((len(unique), 3))
        for i in range(3): np.add.at(normals, indices[:, i], face_normals)
        normals /= np.maximum(np.linalg.norm(normals, axis=1, keepdims=True), 1e-12)
        material = len(doc['materials'])
        doc['materials'].append({'name': 'character_cull_' + str(mode), 'doubleSided': mode == 0,
            'alphaMode': 'MASK', 'alphaCutoff': .04,
            'pbrMetallicRoughness': {'baseColorTexture': {'index': 0}, 'metallicFactor': 0,
                                     'roughnessFactor': 1}})
        doc['meshes'][0]['primitives'].append({'attributes': {
            'POSITION': accessor(unique[:, :3], 'VEC3', target=34962),
            'NORMAL': accessor(normals, 'VEC3', target=34962),
            'TEXCOORD_0': accessor(unique[:, 3:], 'VEC2', target=34962)},
            'indices': accessor(indices.reshape(-1), 'SCALAR', 5123, 34963), 'material': material, 'mode': 4})

    times = np.linspace(0, 2.4, 49)
    translations, rotations, scales = [], [], []
    for t in times:
        phase = t / 2.4 * math.tau
        yaw, roll = .08 * math.sin(phase), .025 * math.sin(phase)
        sy, cy, sz, cz = math.sin(yaw / 2), math.cos(yaw / 2), math.sin(roll / 2), math.cos(roll / 2)
        translations.append([0, 1 + .14 * math.sin(phase), 0])
        rotations.append([-sz * sy, cz * sy, sz * cy, cz * cy])
        scales.append([1, 1 + .006 * (1 - math.cos(phase)), 1])
    time_accessor = accessor(times, 'SCALAR')
    animation = {'name': 'CharacterHomeLoop', 'channels': [], 'samplers': []}
    for path, values, kind in [('translation', translations, 'VEC3'), ('rotation', rotations, 'VEC4'), ('scale', scales, 'VEC3')]:
        animation['channels'].append({'sampler': len(animation['samplers']), 'target': {'node': 0, 'path': path}})
        animation['samplers'].append({'input': time_accessor, 'output': accessor(values, kind), 'interpolation': 'LINEAR'})
    doc['animations'] = [animation]
    doc['buffers'] = [{'uri': 'banner.bin', 'byteLength': len(binary)}]
    (OUT / 'banner.bin').write_bytes(binary)
    (OUT / 'banner.gltf').write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding='utf8')
    glb = json.loads(json.dumps(doc)); glb['buffers'][0].pop('uri')
    image_data = BytesIO(); texture.save(image_data, format='PNG')
    glb['images'][0] = {'bufferView': view(image_data.getvalue()), 'mimeType': 'image/png'}
    glb['bufferViews'] = doc['bufferViews']; glb['buffers'][0]['byteLength'] = len(binary)
    js = json.dumps(glb, separators=(',', ':')).encode(); js += b' ' * (-len(js) % 4)
    binary.extend(b'\0' * (-len(binary) % 4))
    (OUT / 'banner.glb').write_bytes(struct.pack('<4sII', b'glTF', 2, 28 + len(js) + len(binary)) +
        struct.pack('<I4s', len(js), b'JSON') + js + struct.pack('<I4s', len(binary), b'BIN\0') + binary)
    with wave.open(str(OUT / 'silence.wav'), 'wb') as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100); w.writeframes(b'\0' * 44100 * 4)

    # Icon is rendered from the same character, then fitted to native SMDH sizes.
    portrait = render(bones, batches, vs, atlas, 0, 0).crop((140, 40, 340, 240)).convert('RGB')
    draw = ImageDraw.Draw(portrait)
    draw.ellipse((141, 141, 195, 195), fill=(73, 201, 194), outline=(240, 255, 251), width=4)
    draw.line((168, 151, 168, 168, 180, 175), fill=(18, 22, 38), width=5)
    portrait.resize((48, 48), Image.Resampling.LANCZOS).save(OUT / 'icon.png')
    portrait.resize((24, 24), Image.Resampling.LANCZOS).save(OUT / 'icon-small.png')
    report = {'source': 'character/model.sgch', 'triangles': len(vs) // 3,
              'animationSeconds': 2.4, 'animationChannels': 3, 'skinAttributes': 0,
              'textureSize': 128, 'hardwareTested': False,
              'scope': 'Generated source assets only; see cgfx-inspection.json and release/cia/validation.json for binary checks'}
    (OUT / 'source-validation.json').write_text(json.dumps(report, indent=2), encoding='utf8')
    print(json.dumps(report))

if __name__ == '__main__': export()
