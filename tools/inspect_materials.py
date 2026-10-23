import bpy,json,sys
from pathlib import Path
d={}
for m in bpy.data.materials:
    if not m.node_tree:continue
    d[m.name]=[{'name':n.name,'type':n.type,'image':n.image.filepath if n.type=='TEX_IMAGE' and n.image else None,
      'inputs':[{ 'name':s.name,'value':list(s.default_value) if hasattr(s,'default_value') and not isinstance(s.default_value,(str,float,int,bool)) else getattr(s,'default_value',None),
                  'links':[(l.from_node.name,l.from_socket.name) for l in s.links]} for s in n.inputs]} for n in m.node_tree.nodes]
Path(sys.argv[-1]).write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf8')
