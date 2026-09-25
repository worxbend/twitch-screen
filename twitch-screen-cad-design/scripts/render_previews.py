"""Blender renders of exported CAD meshes, in millimetre world coordinates."""
import json
import math
import os
import sys
from pathlib import Path
import bpy
from mathutils import Vector

sys.path.insert(0, str(Path(__file__).resolve().parent))
from render_settings import PREVIEW_SIZE

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'output'
p = json.loads((ROOT/'parameters.json').read_text())
scene = bpy.context.scene
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
scene.render.engine = 'CYCLES'
scene.cycles.samples = 48
scene.cycles.use_denoising = True
scene.render.resolution_x, scene.render.resolution_y = PREVIEW_SIZE
scene.render.resolution_percentage = 100
scene.world.color = (.25,.25,.25)
scene.view_settings.view_transform = 'AgX'
scene.view_settings.exposure = .6
scene.render.image_settings.file_format = 'PNG'


def mat(name,color,metal=0,rough=.4):
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*color,1)
    m.use_nodes = True
    b = m.node_tree.nodes.get('Principled BSDF')
    b.inputs['Base Color'].default_value = (*color,1)
    b.inputs['Metallic'].default_value = metal
    b.inputs['Roughness'].default_value = rough
    return m


materials = {
    'shell':mat('Warm porcelain PETG',(.72,.70,.64),0,.33),
    'base':mat('Charcoal PETG',(.025,.032,.040),0,.38),
    'lcd_retainer':mat('Retainer PETG',(.18,.20,.23),0,.4),
    'face_bezel':mat('Matte black face insert',(.011,.009,.016),0,.4),
    'pcb':mat('Solder mask',(.012,.12,.115),0,.4),
    'metal':mat('Brushed metal',(.42,.45,.49),.85,.25),
    'lcd_glass':mat('Display glass',(.009,.007,.017),.28,.14),
    'plastic':mat('Connector polymer',(.07,.075,.08),0,.45),
    'lcd_connector':mat('Connector ivory',(.68,.65,.52),0,.5),
    'lcd_brass_mounts':mat('LCD brass mounting points',(.45,.27,.075),.8,.28),
}
objects = {}
for name,desc in json.loads((OUT/'render_scene.json').read_text()).items():
    bpy.ops.wm.stl_import(filepath=str(OUT/desc['file']))
    obj = bpy.context.object
    obj.name = name
    obj.scale = (.001,)*3
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    key = name if name in materials else 'pcb' if 'pcb' in name else 'metal' if name in ('esp32_shield','usb_socket') else 'plastic'
    obj.data.materials.append(materials[key])
    # Keep planar hardware crisp; smooth the actual curved CAD surfaces only.
    if name in ('shell','lcd_glass','lcd_retainer','base','face_bezel'):
        obj.data.set_sharp_from_angle(angle=math.radians(35))
        for poly in obj.data.polygons:
            poly.use_smooth = True
        normals = obj.modifiers.new('Weighted surface normals','WEIGHTED_NORMAL')
        normals.keep_sharp = True
    objects[name] = obj

floor_mat = mat('Studio background',(.18,.205,.23),0,.85)
bpy.ops.mesh.primitive_plane_add(size=2)
floor = bpy.context.object
floor.name = 'Studio floor'
floor.location.z = -.0013
floor.data.materials.append(floor_mat)


def area(name,pos,energy,size):
    data = bpy.data.lights.new(name,'AREA')
    data.energy = energy
    data.shape = 'DISK'
    data.size = size
    obj = bpy.data.objects.new(name,data)
    scene.collection.objects.link(obj)
    obj.location = Vector(pos)*.001
    obj.rotation_euler = (Vector((0,0,.035))-obj.location).to_track_quat('-Z','Y').to_euler()


area('Large softbox',(-100,-110,180),.15,.13)
area('Right fill',(120,-40,100),.07,.1)
area('Rear rim',(20,140,180),.2,.09)
cam_data = bpy.data.cameras.new('Camera')
camera = bpy.data.objects.new('Camera',cam_data)
scene.collection.objects.link(camera)
scene.camera = camera
cam_data.type = 'ORTHO'
cam_data.clip_start = .001
cam_data.clip_end = 5


def render(name,position,target=(0,4,36),scale=128):
    requested = os.environ.get('TWITCH_RENDER_ONLY')
    if requested and name not in requested.split(','):
        return
    camera.location = Vector(position)*.001
    camera.rotation_euler = (Vector(target)*.001-camera.location).to_track_quat('-Z','Y').to_euler()
    cam_data.ortho_scale = scale*.001
    scene.render.filepath = str(OUT/'preview'/f'{name}.png')
    bpy.ops.render.render(write_still=True)


render('01_front_hero',(120,-165,105))
render('02_rear_usb',(100,170,82))
render('03_side',(180,0,65))
render('04_front',(0,-180,100))

# Actual mesh Boolean section, not a transparent exterior hiding fit problems.
bpy.ops.mesh.primitive_cube_add(size=1,location=(.16,0,.06))
cut = bpy.context.object
cut.name = 'Cutaway tool'
cut.dimensions = (.32,.4,.3)
bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
cut.hide_render = True
for name in ('shell','base','lcd_retainer','face_bezel'):
    mod = objects[name].modifiers.new('Right half removed for inspection','BOOLEAN')
    mod.operation = 'DIFFERENCE'
    mod.solver = 'EXACT'
    mod.object = cut
render('05_section',(160,-90,90))
for name in ('shell','base','lcd_retainer','face_bezel'):
    objects[name].modifiers.remove(objects[name].modifiers.get('Right half removed for inspection'))

objects['shell'].location.z = .055
objects['face_bezel'].location = (0,-.02,.075)
objects['lcd_retainer'].location = (-.07,-.01,.02)
objects['cable_route'].hide_render = True
for name in ('lcd_pcb','lcd_glass','lcd_connector','lcd_brass_mounts'):
    objects[name].location += Vector((0,-.035,.09))
render('06_exploded',(150,-185,125),target=(0,-2,76),scale=220)
for obj in objects.values():
    obj.location = (0,0,0)
    obj.hide_render = False

for name in ('shell','base','lcd_retainer','face_bezel'):
    for key,obj in objects.items():
        obj.hide_render = key != name
    if name == 'base':
        render('part_base',(85,-110,120),target=(0,4,6),scale=112)
    elif name == 'lcd_retainer':
        render('part_lcd_retainer',(45,-110,95),target=(0,0,48),scale=78)
    elif name == 'face_bezel':
        render('part_face_bezel',(45,-110,95),target=(0,-6,55),scale=78)
    else:
        floor.hide_render = True
        render('part_shell_interior',(75,120,-48),target=(0,0,32),scale=120)
        floor.hide_render = False
for obj in objects.values():
    obj.hide_render = False
cut.hide_viewport = True
camera.location = Vector((120,-165,105))*.001
camera.rotation_euler = (Vector((0,4,36))*.001-camera.location).to_track_quat('-Z','Y').to_euler()
cam_data.ortho_scale = .128
scene.render.filepath = str(OUT/'preview'/'01_front_hero.png')
if not os.environ.get('TWITCH_SKIP_BLEND_SAVE'):
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'preview'/'TwitchScreen.blend'))
print('RENDERS_COMPLETE',flush=True)
