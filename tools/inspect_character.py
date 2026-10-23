import bpy, json, sys
from pathlib import Path
out = Path(sys.argv[sys.argv.index('--') + 1])
data = {'file': bpy.data.filepath, 'objects': [], 'actions': [a.name for a in bpy.data.actions]}
for o in bpy.data.objects:
    d = {'name': o.name, 'type': o.type, 'dimensions': list(o.dimensions)}
    if o.type == 'ARMATURE':
        d['bones'] = [{'name': b.name, 'parent': b.parent.name if b.parent else None, 'head': list(b.head_local), 'tail': list(b.tail_local), 'deform': b.use_deform} for b in o.data.bones]
    if o.type == 'MESH':
        d.update(vertices=len(o.data.vertices), polygons=len(o.data.polygons), materials=[m.name if m else '' for m in o.data.materials], modifiers=[{'name': m.name, 'type': m.type} for m in o.modifiers])
    data['objects'].append(d)
out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
