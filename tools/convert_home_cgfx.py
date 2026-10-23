"""Convert our glTF, fix rigid node bindings/materials, then verify written CGFX."""
import argparse, hashlib, importlib.util, itertools, json, struct, sys
from pathlib import Path
from cgfx_inspect import inspect_bytes, compare_source

ROOT=Path(__file__).resolve().parents[1]
PIN='1f78850086f3a77c41e07162e842f97a5bf3c18a'

def load_converter(repo):
    # Preload the host Pillow: a Store-Python venv can contain another ABI's .pyd.
    from PIL import Image
    try:
        import gltflib
    except ModuleNotFoundError:
        dependencies=ROOT/'tools/homebrew/pycgfx-venv/Lib/site-packages'
        if not dependencies.is_dir(): raise RuntimeError('Install gltflib and Pillow as described in home-menu/README.md')
        sys.path.append(str(dependencies))
        import gltflib
    if not hasattr(itertools,'batched'):
        def batched(iterable,n):
            if n<1: raise ValueError('Batch size must be positive')
            iterator=iter(iterable)
            while batch:=tuple(itertools.islice(iterator,n)): yield batch
        itertools.batched=batched
    sys.path.insert(0,str(repo))
    spec=importlib.util.spec_from_file_location('studygadget_pycgfx',repo/'main.py')
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module,gltflib

def convert(source,output,repo,report_path):
    converter,gltflib=load_converter(repo)
    head=(repo/'.git/HEAD').read_text(encoding='utf8').strip() if (repo/'.git/HEAD').is_file() else ''
    if head != PIN: raise RuntimeError('Expected pinned pycgfx commit '+PIN+', found '+head)
    gltf=gltflib.GLTF.load(str(source.resolve()),load_file_resources=True)
    if gltf.model.skins or any(n.skin is not None for n in gltf.model.nodes):
        raise ValueError('The HOME banner must not contain skins')
    if not gltf.model.animations: raise ValueError('No banner animation')
    for mesh in gltf.model.meshes:
        for primitive in mesh.primitives:
            if any(v is not None for k,v in vars(primitive.attributes).items() if k.startswith(('JOINTS','WEIGHTS'))):
                raise ValueError('Bone indices or weights remain in glTF')
    cgfx=converter.convert_gltf(gltf)
    from cgfx.mtob import ColorFloat
    fixes=[]
    geometry_fixes=[]
    if len(gltf.model.meshes)!=1 or len(gltf.model.nodes)!=1:
        raise ValueError('This HOME profile expects one rigid source mesh node')
    source_primitives=gltf.model.meshes[0].primitives
    for model_name in cgfx.data.models:
        model=cgfx.data.models[model_name]
        for mesh in model.meshes.data.contents:
            shape=model.shapes.data.contents[mesh.shape_index]
            names=set()
            for ps in shape.primitive_sets.data.contents:
                if ps.skinning_mode!=0: raise ValueError('Cannot fix a soft-skinned primitive by renaming')
                related=ps.related_bones.data.contents
                if len(related)!=1: raise ValueError('Rigid primitive must bind exactly one node')
                names.add(model.skeleton.bones[related[0]].name)
            if len(names)!=1: raise ValueError('Ambiguous mesh-node binding')
            node=next(iter(names))
            fixes.append({'mesh':mesh.name,'oldBinding':mesh.mesh_node_name,'newBinding':node})
            mesh.mesh_node_name=node
            # Pinned pycgfx reuses the last material's doubleSided flag in its
            # primitive loop. Rebuild each stream from the original first half.
            primitive_source=source_primitives[mesh.shape_index]
            source_material=gltf.model.materials[primitive_source.material]
            vertex_count=gltf.model.accessors[primitive_source.attributes.POSITION].count
            index_count=gltf.model.accessors[primitive_source.indices].count
            double_sided=bool(source_material.doubleSided)
            if len(shape.primitive_sets.data.contents)!=1:
                raise ValueError('Expected one primitive set per rigid shape')
            for attribute in shape.vertex_attributes.data.contents:
                if attribute.format_type!=0x1406:
                    raise ValueError('Expected float vertex attributes')
                byte_count=vertex_count*attribute.components_count*4
                if len(attribute.vertex_stream_data) not in (byte_count,byte_count*2):
                    raise ValueError('Unexpected converted vertex count')
                original_data=attribute.vertex_stream_data[:byte_count]
                attribute.vertex_stream_data=original_data
                if double_sided:
                    if attribute.usage==1:
                        normal_values=struct.unpack('<'+'f'*(byte_count//4),original_data)
                        attribute.vertex_stream_data+=struct.pack('<'+'f'*len(normal_values),*(-v for v in normal_values))
                    else:
                        attribute.vertex_stream_data+=original_data
            for ps in shape.primitive_sets.data.contents:
                for primitive in ps.primitives.data.contents:
                    for stream in primitive.index_streams.data.contents:
                        if stream.data_type not in (0x1401,0x1403):
                            raise ValueError('Unsupported face index type')
                        code,width=('B',1) if stream.data_type==0x1401 else ('H',2)
                        original_indices=struct.unpack('<'+code*index_count,stream.face_data[:width*index_count])
                        if max(original_indices)>=vertex_count:
                            raise ValueError('Original index is outside source vertex count')
                        indices=list(original_indices)
                        if double_sided:
                            indices.extend(v+vertex_count for v in reversed(original_indices))
                        code='H' if max(indices)>255 else 'B'
                        stream.data_type=0x1403 if code=='H' else 0x1401
                        stream.face_data=struct.pack('<'+code*len(indices),*indices)
            mesh.name=f'{node}_{source_material.name}_{mesh.shape_index}'
            shape.name=mesh.name
            geometry_fixes.append({'shape':mesh.shape_index,'doubleSided':double_sided,
                                   'sourceVertices':vertex_count,'outputVertices':vertex_count*(2 if double_sided else 1),
                                   'sourceTriangles':index_count//3,'outputTriangles':index_count//3*(2 if double_sided else 1)})
        for material_name in model.materials:
            material=model.materials[material_name]
            material.material_color.specular=[ColorFloat(0,0,0,0),ColorFloat(0,0,0,0)]
            # Preserve diffuse lighting/alpha/culling. Remove only additive specular TEV.
            stage=material.fragment_shader.texture_combiners[2]
            stage.src_rgb=0xFFF; stage.combine_rgb=0; stage.scale_rgb=0
    output.parent.mkdir(parents=True,exist_ok=True)
    original=output.read_bytes() if output.is_file() else None
    written=converter.write(cgfx)
    result=inspect_bytes(written)
    if result['passed']: compare_source(result,written,source)
    result['sourceGlb']=str(source.relative_to(ROOT)) if source.is_relative_to(ROOT) else str(source)
    result['sourceSha256']=hashlib.sha256(source.read_bytes()).hexdigest()
    result['pycgfxCommit']=PIN
    result['corrections']={'meshBindings':fixes,'doubleSidedGeometry':geometry_fixes,'specularColors':'zero','specularTevStage':'previous RGB passthrough'}
    if not result['passed']:
        report_path.parent.mkdir(parents=True,exist_ok=True)
        report_path.write_text(json.dumps(result,indent=2),encoding='utf8')
        raise ValueError('Corrected CGFX failed inspection: '+'; '.join(result['errors']))
    if original is not None and original!=written:
        backup=ROOT/'diagnostics/home-menu/banner-before-correction.cgfx'
        backup.parent.mkdir(parents=True,exist_ok=True)
        if not backup.exists(): backup.write_bytes(original)
    # Validate a temporary file before replacing the user's converted output.
    temporary=output.with_suffix('.cgfx.tmp')
    temporary.write_bytes(written)
    reread=inspect_bytes(temporary.read_bytes())
    if not reread['passed']: raise ValueError('CGFX verification after write failed')
    temporary.replace(output)
    report_path.parent.mkdir(parents=True,exist_ok=True)
    report_path.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps({'passed':True,'bytes':len(written),'meshBindingsFixed':len(fixes),
                      'sha256':result['sha256'],'output':str(output)},ensure_ascii=True))
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path,default=ROOT/'home-menu/banner.glb')
    p.add_argument('--output',type=Path,default=ROOT/'home-menu/banner.cgfx')
    p.add_argument('--pycgfx',type=Path,default=ROOT/'tools/homebrew/pycgfx')
    p.add_argument('--report',type=Path,default=ROOT/'home-menu/cgfx-validation.json')
    a=p.parse_args(); convert(a.input,a.output,a.pycgfx,a.report)

if __name__=='__main__': main()

