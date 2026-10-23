"""Blender --background --factory-startup file.blend --python tools/export_character.py -- output_dir
Exports a versioned, textured, four-weight skeletal mesh. Source file is never overwritten.
"""
import bpy, sys, json, struct, zlib, math, hashlib
from pathlib import Path
import numpy as np
from mathutils import Matrix, Vector, Quaternion

OUT = Path(sys.argv[sys.argv.index('--') + 1]).resolve()
OUT.mkdir(parents=True, exist_ok=True)
MAP = {'ボーン':'hips', 'ボーン.004':'spine', 'ボーン.003':'chest',
       'ボーン.001':'neck', 'ボーン.002':'head', '髪1':'hair.back.01', '髪2':'hair.back.02',
       '髪3.L':'hair.side.L', '髪3.R':'hair.side.R'}
for side in ('L','R'):
    for old,new in [('008','shoulder'),('009','upper_arm'),('010','forearm'),('011','hand'),
                    ('005','upper_leg'),('006','lower_leg'),('007','foot')]:
        MAP[f'ボーン.{old}.{side}'] = f'{new}.{side}'
rigs = [o for o in bpy.context.scene.objects if o.type == 'ARMATURE']
if len(rigs) != 1:
    raise RuntimeError('Expected exactly one armature; select a character-only scene')
rig = rigs[0]
if rig.mode != 'OBJECT': bpy.ops.object.mode_set(mode='OBJECT')
for old,new in MAP.items():
    if old in rig.data.bones:
        if new in rig.data.bones: raise RuntimeError('Duplicate canonical bone: '+new)
        rig.data.bones[old].name = new
# Blender updates weights, constraints and animation paths during bone renaming.
rig.name = 'CharacterRig'
bpy.context.view_layer.objects.active = rig
bpy.ops.object.mode_set(mode='EDIT')
for side in ('L','R'):
    b = rig.data.edit_bones.get('upper_leg.'+side)
    if b and not b.parent:
        b.use_connect = False
        b.parent = rig.data.edit_bones.get('hips')
bpy.ops.object.mode_set(mode='OBJECT')
bones = []
def visit(b):
    bones.append(b)
    for c in b.children: visit(c)
for b in rig.data.bones:
    if not b.parent: visit(b)
if not 1 <= len(bones) <= 96: raise RuntimeError('Bone budget exceeded')
ids = {b.name:i for i,b in enumerate(bones)}
# Blender Z-up -> runtime Y-up, front -Y becomes +Z.
C = Matrix(((1,0,0,0),(0,0,1,0),(0,-1,0,0),(0,0,0,1)))
meshes = [o for o in bpy.context.scene.objects if o.type == 'MESH'
          and not any(m and m.name in ('イラスト3','下絵透過') for m in o.data.materials)]
attachments = []
for o in meshes:
    for old,new in MAP.items():
        if old in o.vertex_groups and new not in o.vertex_groups: o.vertex_groups[old].name = new
    if not any(m.type == 'ARMATURE' for m in o.modifiers):
        # Accessories without weights use rigid binding, preserving their world transform.
        center=sum((o.matrix_world@Vector(v) for v in o.bound_box),Vector())/8
        def distance(b):
            h=rig.matrix_world@b.head_local;t=rig.matrix_world@b.tail_local;seg=t-h
            q=max(0,min(1,(center-h).dot(seg)/max(seg.length_squared,1e-10)))
            return (center-(h+q*seg)).length_squared
        name=min(bones,key=distance).name
        if o.name.startswith('hair'):name='head'
        if o.name in ('clothes','skirt'):name='chest' if o.name=='clothes' else 'hips'
        vg = o.vertex_groups.get(name) or o.vertex_groups.new(name=name)
        vg.add(list(range(len(o.data.vertices))), 1.0, 'REPLACE')
        mod = o.modifiers.new('Character deformation', 'ARMATURE'); mod.object = rig
        attachments.append({'object':o.name,'bone':name})

materials = list(dict.fromkeys(m for o in meshes for m in o.data.materials if m))
if len(materials) > 16: raise RuntimeError('Maximum 16 materials')
N, TILE = 1024, 256
atlas = np.ones((N,N,4), dtype=np.uint8)*255
mat_report = []
def bake_socket(material, socket_name):
    """Bake the evaluated node graph, including ramps, mixes and node groups.
    Sampling the first image node is incorrect for layered painted materials.
    """
    clone=material.copy();clone.use_nodes=True;nodes=clone.node_tree.nodes;links=clone.node_tree.links
    principled=next((n for n in nodes if n.type=='BSDF_PRINCIPLED'),None)
    output=next((n for n in nodes if n.type=='OUTPUT_MATERIAL' and n.is_active_output),None)
    if not output:return None
    surface=output.inputs['Surface'].links[0].from_socket if output.inputs['Surface'].is_linked else None
    if surface and surface.type in ('RGBA','VALUE'):
        # Blender implicitly turns a color connected directly to Surface into emission.
        # Disconnected Principled nodes in the material must not override that output.
        if socket_name=='Alpha':return np.ones((TILE,TILE,4),dtype=np.float32)
        if surface.node.type in ('RGB','VALUE'):
            value=surface.default_value
            rgba=(value,value,value,1) if isinstance(value,float) else value
            return np.broadcast_to(rgba,(TILE,TILE,4)).astype(np.float32).copy()
        socket=surface
    elif principled:
        socket=principled.inputs[socket_name]
    else:return None
    emission=nodes.new('ShaderNodeEmission')
    if surface and surface.type in ('RGBA','VALUE'):links.new(surface,emission.inputs['Color'])
    elif socket.is_linked:links.new(socket.links[0].from_socket,emission.inputs['Color'])
    else:
        value=socket.default_value
        emission.inputs['Color'].default_value=(value,value,value,1) if isinstance(value,float) else value
    links.new(emission.outputs[0],output.inputs['Surface'])
    img=bpy.data.images.new('SG Bake '+socket_name,TILE,TILE,alpha=True,float_buffer=True)
    node=nodes.new('ShaderNodeTexImage');node.image=img;nodes.active=node
    for n in nodes:n.select=(n==node)
    bpy.ops.mesh.primitive_plane_add(size=2,location=(100,100,100))
    plane=bpy.context.object;plane.data.materials.append(clone)
    bpy.context.scene.render.engine='CYCLES';bpy.context.scene.cycles.samples=1;bpy.context.scene.cycles.device='CPU'
    bpy.context.scene.render.bake.margin=0
    bpy.ops.object.bake(type='EMIT',use_clear=True,margin=0)
    pix=np.empty(TILE*TILE*4,dtype=np.float32);img.pixels.foreach_get(pix)
    result=pix.reshape(TILE,TILE,4).copy()
    bpy.data.objects.remove(plane,do_unlink=True);bpy.data.materials.remove(clone);bpy.data.images.remove(img)
    return result
for i,m in enumerate(materials):
    color = list(m.diffuse_color)
    image = None
    if m.use_nodes:
        bsdf = next((n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED'),None)
        if bsdf:
            color = list(bsdf.inputs['Base Color'].default_value)
            # Prefer the image that is actually connected to Base Color.
            def find_image(node, seen):
                if node in seen: return None
                seen.add(node)
                if node.type == 'TEX_IMAGE' and node.image: return node.image
                for inp in node.inputs:
                    for link in inp.links:
                        found = find_image(link.from_node, seen)
                        if found: return found
            for link in bsdf.inputs['Base Color'].links:
                image = find_image(link.from_node,set())
                if image: break
    tile = np.empty((TILE,TILE,4),dtype=np.uint8)
    baked=bake_socket(m,'Base Color')
    if baked is not None:
        alpha=bake_socket(m,'Alpha')
        rgb=baked[:,:,:3]
        baked[:,:,:3]=np.where(rgb<=.0031308,rgb*12.92,1.055*np.maximum(rgb,0)**(1/2.4)-.055)
        baked[:,:,3]=alpha[:,:,0] if alpha is not None else color[3]
        tile[:]=np.clip(baked*255,0,255).astype(np.uint8)
    elif image and image.size[0] and image.size[1]:
        w,h = image.size
        pix = np.empty(w*h*4,dtype=np.float32); image.pixels.foreach_get(pix)
        pix = pix.reshape(h,w,4)
        # Blender pixels are scene-linear; encode RGB to sRGB for unlit runtime rendering.
        rgb = pix[:,:,:3]
        pix[:,:,:3] = np.where(rgb <= .0031308, rgb*12.92, 1.055*np.maximum(rgb,0)**(1/2.4)-.055)
        ys=np.linspace(0,h-1,TILE).astype(int); xs=np.linspace(0,w-1,TILE).astype(int)
        tile[:] = np.clip(pix[ys[:,None],xs[None,:]]*255,0,255).astype(np.uint8)
    else:
        rgb=np.array(color[:3]); rgb=np.where(rgb<=.0031308,rgb*12.92,1.055*np.maximum(rgb,0)**(1/2.4)-.055)
        tile[:] = np.clip(np.r_[rgb,color[3]]*255,0,255).astype(np.uint8)
    x=(i%4)*TILE; y=(i//4)*TILE
    atlas[y:y+TILE,x:x+TILE]=tile
    mat_report.append({'material':m.name,'image':image.name if image else None,'nodeGraphBaked':baked is not None})
# 8x8 Morton tiled ABGR texture, directly consumed by PICA200.
tex = bytearray(N*N*4)
for y in range(N):
    for x in range(N):
        morton=sum(((x>>b)&1)<<(2*b) | ((y>>b)&1)<<(2*b+1) for b in range(3))
        offset=((y//8)*(N//8)*64+(x//8)*64+morton)*4
        r,g,b,a=atlas[y,x]; tex[offset:offset+4]=bytes((a,b,g,r))
worlds=[C@rig.matrix_world@b.matrix_local for b in bones]
payload=bytearray()
for i,b in enumerate(bones):
    p=ids[b.parent.name] if b.parent else -1
    local=worlds[p].inverted()@worlds[i] if p>=0 else worlds[i]
    inv=worlds[i].inverted()
    payload+=struct.pack('<32si32f', b.name.encode('ascii').ljust(32,b'\0'),p,
                         *[v for row in local for v in row],*[v for row in inv for v in row])
vertices=[]; points=[]; buckets={0:[],1:[],2:[]}
for o in meshes:
    # Evaluate mirror/smoothing but disable skinning to export the bind mesh.
    mods=[m for m in o.modifiers if m.type=='ARMATURE']
    old=[m.show_viewport for m in mods]
    for m in mods: m.show_viewport=False
    bpy.context.view_layer.update()
    eo=o.evaluated_get(bpy.context.evaluated_depsgraph_get()); mesh=eo.to_mesh(); mesh.calc_loop_triangles()
    transform=C@o.matrix_world
    for tri in mesh.loop_triangles:
        mat=o.data.materials[tri.material_index] if len(o.data.materials) else None
        mi=materials.index(mat) if mat in materials else 0
        # Explicit material property wins. Inverted hulls need BACK culling;
        # hair cards can retain NONE. Winding is preserved (C has determinant +1).
        outline=any(s in (o.name+' '+(mat.name if mat else '')).lower() for s in ('outline','輪郭','ふち','外殻'))
        cull=str(mat.get('sg_cull','back' if outline or (mat and mat.use_backface_culling) else 'none')) if mat else 'none'
        if cull not in ('none','back','front'):raise RuntimeError('sg_cull must be none/back/front')
        mode={'none':0,'back':1,'front':2}[cull]
        for li in tri.loops:
            loop=mesh.loops[li]; v=mesh.vertices[loop.vertex_index]
            pos=transform@v.co; points.append(pos)
            uv=mesh.uv_layers.active.data[li].uv if mesh.uv_layers.active else (0.5,0.5)
            u=((mi%4)*TILE+1+(float(uv[0])%1)*(TILE-2))/N
            vv=((mi//4)*TILE+1+(float(uv[1])%1)*(TILE-2))/N
            weights=sorted([(ids[o.vertex_groups[g.group].name],g.weight) for g in v.groups
                            if g.group<len(o.vertex_groups) and o.vertex_groups[g.group].name in ids and g.weight>0],key=lambda x:-x[1])[:4]
            if not weights: weights=[(ids.get('hips',0),1.0)]
            total=sum(w for _,w in weights); indices=[i for i,w in weights]; ws=[w/total for _,w in weights]
            indices += [0]*(4-len(indices)); ws += [0.]*(4-len(ws))
            buckets[mode].append(struct.pack('<9f4B4f',*pos,u,vv,1.,1.,1.,1.,*indices,*ws))
    eo.to_mesh_clear()
    for m,visible in zip(mods,old):m.show_viewport=visible
batch_report=[]
for mode,vs in buckets.items():
    if vs:
        batch_report.append((len(vertices),len(vs),mode));vertices.extend(vs)
if len(vertices)>24000:raise RuntimeError('Triangle budget exceeded: '+str(len(vertices)//3))
for batch in batch_report:payload+=struct.pack('<3I',*batch)
payload+=b''.join(vertices)+tex
skeleton=zlib.crc32('|'.join(b.name+':'+str(ids[b.parent.name] if b.parent else -1) for b in bones).encode())
header=struct.pack('<4s8I',b'SGCH',1,skeleton,len(bones),len(vertices),N,len(batch_report),zlib.crc32(payload),len(payload))
(OUT/'model.sgch').write_bytes(header+payload)
(OUT/'bone-map.json').write_text(json.dumps({'renamed':MAP,'attachments':attachments,'skeleton':skeleton,'materials':mat_report},ensure_ascii=False,indent=2),encoding='utf8')
(OUT/'manifest.json').write_text(json.dumps({'schema':1,'revision':1,'skeleton':skeleton,'bones':[b.name for b in bones],
    'triangles':len(vertices)//3,'modelBytes':len(header+payload),'crc':zlib.crc32(payload),'batches':batch_report,'sha256':hashlib.sha256(header+payload).hexdigest(),
    'bounds':[[min(p[i] for p in points) for i in range(3)],[max(p[i] for p in points) for i in range(3)]]},indent=2),encoding='utf8')
# Pack images so future model edits don't depend on external absolute paths.
bpy.ops.file.pack_all()
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'character-renamed.blend'))
print('CHARACTER_EXPORT_OK',len(bones),len(vertices)//3,skeleton)
