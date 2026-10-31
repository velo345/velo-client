"""Fit check: renders bodywear .vprop items on a full Minecraft player (front, 3/4, side, back, top) per item.

    blender -b --python tools/cosmetics/wear/fitcheck.py -- <out-prefix> <zone: head|body|feet|arm> <ids...>
    -> <out-prefix>_<id>_<view>.png  (tile them with PIL to compare)
"""
import bpy, json, math, struct, sys
from pathlib import Path
from mathutils import Vector
W = Path(__file__).resolve().parents[3] / "server/cosmetics-seed/wear"
args = sys.argv[sys.argv.index("--") + 1:]
out, view, ids = args[0], args[1], args[2:]
bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
sc.render.engine = "BLENDER_WORKBENCH"
sc.display.shading.light = "STUDIO"
sc.display.shading.color_type = "TEXTURE"
sc.display.shading.show_cavity = True
PIV = {"head": (0, 0, 0), "body": (0, 0, 0), "rightArm": (-5, 2, 0), "leftArm": (5, 2, 0), "rightLeg": (-1.9, 12, 0), "leftLeg": (1.9, 12, 0)}
def mat(name, rgb=None, img=None):
    m = bpy.data.materials.new(name); m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    if img:
        t = m.node_tree.nodes.new("ShaderNodeTexImage"); t.image = bpy.data.images.load(str(img)); t.interpolation = "Closest"
        m.node_tree.links.new(t.outputs["Color"], b.inputs["Base Color"])
    else:
        b.inputs["Base Color"].default_value = (*rgb, 1); m.diffuse_color = (*rgb, 1)
    b.inputs["Roughness"].default_value = 0.8
    return m
skin = mat("skin", (0.62, 0.48, 0.38)); shirt = mat("shirt", (0.2, 0.55, 0.6)); pants = mat("pants", (0.2, 0.2, 0.45)); face = mat("face", (0.15, 0.1, 0.1))
def box(x0, x1, y0, y1, z0, z1, m, dx):
    # MC model space box -> Blender (X, Y=z, Z=24-y)
    bpy.ops.mesh.primitive_cube_add(size=1, location=(dx + (x0 + x1) / 2, (z0 + z1) / 2, 24 - (y0 + y1) / 2))
    o = bpy.context.active_object; o.scale = (x1 - x0, z1 - z0, y1 - y0); o.data.materials.append(m)
def player(dx):
    box(-4, 4, -8, 0, -4, 4, skin, dx)
    for ex in (-2, 2):  # eyes, 1px on the front face at MC y -4..-3
        box(ex - 1, ex, -4, -3, -4.05, -3.95, face, dx)
    box(-4, 4, 0, 12, -2, 2, shirt, dx)
    box(-8, -4, -2, 10, -2, 2, skin, dx); box(4, 8, -2, 10, -2, 2, skin, dx)
    box(-3.9, 0.1, 12, 24, -2, 2, pants, dx); box(-0.1, 3.9, 12, 24, -2, 2, pants, dx)
def vprop(path, bone, m, dx):
    d = path.read_bytes(); n = struct.unpack(">i", d[8:12])[0]; off = 12
    vs, uvs = [], []
    for i in range(n):
        x, y, z, _, _, _, u, v = struct.unpack(">8f", d[off:off + 32]); off += 32
        px, py, pz = PIV[bone]
        vs.append((dx + px + x, pz + z, 24 - (py + y))); uvs.append((u, v))
    c = struct.unpack(">i", d[off:off + 4])[0]; off += 4
    idx = struct.unpack(">%di" % c, d[off:off + 4 * c])
    me = bpy.data.meshes.new(path.stem); me.from_pydata(vs, [], [idx[i:i + 3] for i in range(0, c, 3)])
    uv = me.uv_layers.new()
    for poly in me.polygons:
        for li in poly.loop_indices:
            u, v = uvs[me.loops[li].vertex_index]; uv.data[li].uv = (u, 1 - v)
    o = bpy.data.objects.new(path.stem, me); sc.collection.objects.link(o); me.materials.append(m)
for k, i in enumerate(ids):
    dx = k * 40
    player(dx)
    meta = json.loads((W / f"{i}.json").read_text())
    m = mat(i, img=W / f"{i}.png")
    vprop(W / f"{i}.vprop", meta["bone"], m, dx)
    if meta.get("mirror"):
        vprop(W / f"{i}_l.vprop", meta["mirror"], m, dx)
# light + ortho camera
sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN")); sun.data.energy = 3; sc.collection.objects.link(sun)
sun.rotation_euler = (math.radians(50), 0, math.radians(-30 if view != "back" else 150))
w = bpy.data.worlds.new("w"); w.use_nodes = True; w.node_tree.nodes["Background"].inputs["Color"].default_value = (0.5, 0.5, 0.55, 1); sc.world = w
cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam")); sc.collection.objects.link(cam); sc.camera = cam
cam.data.type = "ORTHO"; cam.data.clip_start = 62; cam.data.clip_end = 98
# view = "zone": head | body | feet | arm ; four angles per item
zone = {"head": (30, 16), "body": (17, 20), "feet": (3, 12), "arm": (16, 16)}[view]
sc.render.resolution_x = sc.render.resolution_y = 300
cam.data.ortho_scale = zone[1]
for k, i in enumerate(ids):
    cx = k * 40 + (5 if view == "arm" else 0)
    for name, yaw, pitch in (("front", 0, 0), ("q", -40, 20), ("side", 90, 0), ("back", 180, 0), ("top", 0, 70)):
        a, pt = math.radians(yaw), math.radians(pitch)
        cam.location = (cx + 80 * math.sin(a) * math.cos(pt), -80 * math.cos(a) * math.cos(pt), zone[0] + 80 * math.sin(pt))
        cam.rotation_euler = (math.radians(90 - pitch), 0, a)
        sun.rotation_euler = (math.radians(50), 0, a - math.radians(30))
        sc.render.filepath = f"{out}_{i}_{name}.png"
        bpy.ops.render.render(write_still=True)
