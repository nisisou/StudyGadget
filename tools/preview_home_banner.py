"""Rasterize the exported glTF itself. Preview is not a HOME Menu emulator."""
import json, math
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'home-menu'

def main():
    gltf = json.loads((OUT / 'banner.gltf').read_text(encoding='utf8'))
    data = (OUT / 'banner.bin').read_bytes()
    def get(index):
        acc = gltf['accessors'][index]; v = gltf['bufferViews'][acc['bufferView']]
        components = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[acc['type']]
        return np.frombuffer(data, dtype='<u2' if acc['componentType'] == 5123 else '<f4',
            count=acc['count'] * components, offset=v.get('byteOffset', 0) + acc.get('byteOffset', 0)).reshape(-1, components)
    animation = gltf['animations'][0]
    tracks = {c['target']['path']: get(animation['samplers'][c['sampler']]['output']) for c in animation['channels']}
    texture = np.array(Image.open(OUT / 'banner-atlas.png').convert('RGBA'))
    frames = []
    for frame in range(0, 48, 2):
        x, y, z, w = tracks['rotation'][frame]
        rotation = np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
                             [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                             [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])
        yy, xx = np.mgrid[0:240, 0:400]
        image = np.empty((240, 400, 4), dtype=np.uint8)
        image[:, :, 0] = 18 + yy // 18; image[:, :, 1] = 23 + yy // 13
        image[:, :, 2] = 40 + yy // 10; image[:, :, 3] = 255
        depth = np.full((240, 400), np.inf)
        for primitive in gltf['meshes'][0]['primitives']:
            positions = (get(primitive['attributes']['POSITION']) * tracks['scale'][frame]) @ rotation.T + tracks['translation'][frame]
            uv = get(primitive['attributes']['TEXCOORD_0'])
            zz = 44.786 - positions[:, 2]
            focal = 120 / math.tan(.523599 / 2)
            xy = np.c_[200 + positions[:, 0] / zz * focal, 120 - (positions[:, 1] - 1) / zz * focal]
            double_sided = gltf['materials'][primitive['material']]['doubleSided']
            for ids in get(primitive['indices']).reshape(-1, 3):
                p = xy[ids]; zdepth = zz[ids]; texcoord = uv[ids]
                aedge, bedge = p[1]-p[0], p[2]-p[0]; area = aedge[0]*bedge[1]-aedge[1]*bedge[0]
                if abs(area) < 1e-8 or (not double_sided and area >= 0): continue
                lo = np.maximum(0, np.floor(p.min(axis=0)).astype(int)); hi = np.minimum([399, 239], np.ceil(p.max(axis=0)).astype(int))
                if np.any(lo > hi): continue
                yy, xx = np.mgrid[lo[1]:hi[1]+1, lo[0]:hi[0]+1]; px, py = xx+.5, yy+.5
                a = ((p[1,0]-px)*(p[2,1]-py)-(p[1,1]-py)*(p[2,0]-px))/area
                b = ((p[2,0]-px)*(p[0,1]-py)-(p[2,1]-py)*(p[0,0]-px))/area; c = 1-a-b
                inverse_z = a/zdepth[0] + b/zdepth[1] + c/zdepth[2]
                zvalue = 1 / np.maximum(inverse_z, 1e-8)
                uvvalue = (a[:,:,None]*texcoord[0]/zdepth[0]+b[:,:,None]*texcoord[1]/zdepth[1]+c[:,:,None]*texcoord[2]/zdepth[2])*zvalue[:,:,None]
                uvint = np.clip((uvvalue*128).astype(int), 0, 127)
                colors = texture[uvint[:,:,1], uvint[:,:,0]]
                d = depth[lo[1]:hi[1]+1, lo[0]:hi[0]+1]; out = image[lo[1]:hi[1]+1, lo[0]:hi[0]+1]
                visible = (a>=0)&(b>=0)&(c>=0)&(zvalue<d)&(colors[:,:,3]>10)
                out[visible] = colors[visible]; d[visible] = zvalue[visible]
        frames.append(Image.fromarray(image).convert('RGB'))
    frames[0].save(OUT / 'banner-preview.png')
    frames[0].save(OUT / 'banner-preview.gif', save_all=True, append_images=frames[1:], duration=100, loop=0)
    print('Saved glTF preview (24 frames / 2.4 seconds)')

if __name__ == '__main__': main()
