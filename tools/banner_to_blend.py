"""Blender --background --factory-startup --python tools/banner_to_blend.py"""
from pathlib import Path
import bpy
ROOT = Path(__file__).resolve().parents[1]
scene = bpy.context.scene
scene.render.fps = 20
bpy.ops.import_scene.gltf(filepath=str(ROOT / 'home-menu/banner.glb'))
scene.frame_start = 1
scene.frame_end = 49
bpy.ops.object.camera_add(location=(0, -44.786, 1), rotation=(1.57079632679, 0, 0))
camera = bpy.context.object
camera.name = 'HOME reference camera'
camera.data.type = 'PERSP'
camera.data.lens_unit = 'FOV'
camera.data.angle = .523599
camera.data.sensor_fit = 'VERTICAL'
camera.data.clip_start = 26.5
camera.data.clip_end = 1000
scene.camera = camera
scene.render.resolution_x = 400
scene.render.resolution_y = 240
scene.render.resolution_percentage = 100
scene.frame_set(1)
bpy.ops.file.pack_all()
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'home-menu/banner.blend'))
assert not any(o.type == 'ARMATURE' for o in scene.objects)
assert not any(m.type == 'ARMATURE' for o in scene.objects for m in o.modifiers)
print('BANNER_BLEND_OK: rigid meshes, animation and reference camera')
