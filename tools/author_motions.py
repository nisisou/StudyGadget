"""Bake the runtime motion recipes into editable Blender Actions (source model stays separate)."""
import bpy,sys,math
from pathlib import Path
from mathutils import Matrix,Euler
out=Path(sys.argv[-1]).resolve()
rig=next(o for o in bpy.data.objects if o.type=='ARMATURE')
C=Matrix(((1,0,0,0),(0,0,1,0),(0,-1,0,0),(0,0,0,1)))
rig.animation_data_create()
lines=(out/'motions.sgmo').read_text().splitlines()[1:]
names=['idle','focus','break','success','encourage','sleep']
for index,line in enumerate(lines):
    state,duration,speed,intensity,sway,head,arm,phase=map(float,line.split());state=int(state)
    action=bpy.data.actions.new(f'{names[state]}.{index%8+1:02d}');action.use_fake_user=True
    rig.animation_data.action=action
    action['sg_state']=state;action['sg_recipe']=line
    for frame in range(1,int(duration*15)+2,3):
        t=(frame-1)/15;s=math.sin(t*speed*1.5+phase);q=math.sin(t*speed*.63+phase)
        for pb in rig.pose.bones:
            name=pb.name;x=y=z=0
            if name=='upper_arm.L':z=-1.18
            if name=='upper_arm.R':z=1.18
            if name=='chest':z=q*sway;x=s*.025*intensity*.4
            if name=='head':y=q*head*.4;x=s*head*.3+(.12 if state==1 else 0);z=.18 if state==5 else 0
            if name.startswith('hair.'):x=s*.04*intensity;z=q*sway*.4
            if state in (3,4):
                if name=='upper_arm.L':z+=arm*(.6+.25*s)*intensity
                if name=='forearm.L':z=.5*intensity
                if name=='hand.L':y=s*.4*intensity
                if state==3 and name=='upper_arm.R':z-=arm*.6*intensity
            if state==2 and name=='chest':z+=s*.08*intensity
            if state==5 and name=='chest':x=.06+s*.01
            world=C@rig.matrix_world@pb.bone.matrix_local
            basis=world.to_3x3().normalized()
            rotation=basis.inverted()@Euler((x,y,z),'XYZ').to_matrix()@basis
            pb.rotation_mode='QUATERNION';pb.rotation_quaternion=rotation.to_quaternion()
            pb.keyframe_insert('rotation_quaternion',frame=frame,group=name)
rig.animation_data.action=bpy.data.actions.get('idle.01')
bpy.context.scene.render.fps=15;bpy.context.scene.frame_start=1;bpy.context.scene.frame_end=150;bpy.context.scene.frame_set(1)
for o in bpy.context.scene.objects:
    if o.type=='MESH' and any(m and m.name in ('イラスト3','下絵透過') for m in o.data.materials):o.hide_set(True);o.hide_render=True
bpy.ops.wm.save_as_mainfile(filepath=str(out/'character-motions.blend'))
print('EDITABLE_ACTIONS_OK',len(lines))
