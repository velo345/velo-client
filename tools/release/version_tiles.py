"""Velo launcher version tiles: one small diorama per Minecraft version, built from that version's own textures and
rendered in Cycles (sun + sky, volumetric haze, emissive blocks, glass water, bloom, depth of field).

    blender -b --python tiles.py -- <version> <client.jar> <out.png> [width height samples]

Coordinates: X right, Y away from the camera, Z up; 1 unit = 1 block.
"""
import bpy, math, random, sys, zipfile, os
from pathlib import Path
from mathutils import Vector

args = sys.argv[sys.argv.index("--") + 1:]
VERSION, JAR, OUT = args[0], args[1], args[2]
RW, RH, SAMPLES = (int(args[3]), int(args[4]), int(args[5])) if len(args) > 5 else (1408, 792, 192)
CACHE = Path(os.environ.get("VELO_TILE_CACHE", "/tmp/velo-tile-tex")) / VERSION
CACHE.mkdir(parents=True, exist_ok=True)
random.seed(hash(VERSION) & 0xFFFF)
LEGACY = VERSION in ("1.8.9", "1.9.4")

bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
sc.render.engine = "CYCLES"
prefs = bpy.context.preferences.addons["cycles"].preferences
for kind in ("OPTIX", "CUDA"):
    try:
        prefs.compute_device_type = kind
        prefs.get_devices()
        if any(d.type == kind for d in prefs.devices):
            for d in prefs.devices:
                d.use = d.type == kind
            break
    except TypeError:
        pass
sc.cycles.device = "GPU"
sc.cycles.samples = SAMPLES
sc.cycles.use_denoising = True
sc.cycles.max_bounces = 8
sc.cycles.volume_bounces = 1
sc.cycles.volume_step_rate = 2
sc.render.resolution_x, sc.render.resolution_y = RW, RH
sc.view_settings.view_transform = "AgX"
sc.view_settings.look = "AgX - Medium High Contrast"

ZIP = zipfile.ZipFile(JAR)

# ------------------------------------------------------------------ textures & materials

def tex_path(name):
    """name like 'block/grass_block_top' (modern) or 'blocks/grass_top' (1.8/1.9). Extracted once per version."""
    out = CACHE / (name.replace("/", "__") + ".png")
    if not out.exists():
        data = ZIP.read("assets/minecraft/textures/" + name + ".png")
        out.write_bytes(data)
    return out


IMAGES = {}


def image(name):
    if name not in IMAGES:
        img = bpy.data.images.load(str(tex_path(name)))
        img.colorspace_settings.name = "sRGB"
        IMAGES[name] = img
    return IMAGES[name]


def frame_scale(name):
    """Animated strips (water, lava, sea lantern...) are taller than wide: use the first frame."""
    img = image(name)
    w, h = img.size
    return 1.0 if h <= w else w / h


MATS = {}


def mat(name, tint=None, emit=0.0, alpha=False, rough=0.9, metal=0.0, emit_mask=False, spec=0.4):
    key = (name, tint, emit, alpha, rough, metal, emit_mask)
    if key in MATS:
        return MATS[key]
    m = bpy.data.materials.new(name.split("/")[-1])
    m.use_nodes = True
    nt = m.node_tree
    b = nt.nodes["Principled BSDF"]
    t = nt.nodes.new("ShaderNodeTexImage")
    t.image = image(name)
    t.interpolation = "Closest"
    col = t.outputs["Color"]
    if tint:
        mix = nt.nodes.new("ShaderNodeMix"); mix.data_type = "RGBA"; mix.blend_type = "MULTIPLY"
        mix.inputs["Factor"].default_value = 1.0
        nt.links.new(col, [i for i in mix.inputs if i.name == "A" and i.type == "RGBA"][0])
        [i for i in mix.inputs if i.name == "B" and i.type == "RGBA"][0].default_value = (*lin(tint), 1)
        col = [o for o in mix.outputs if o.name == "Result" and o.type == "RGBA"][0]
    nt.links.new(col, b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    b.inputs["Specular IOR Level"].default_value = spec
    if alpha:
        nt.links.new(t.outputs["Alpha"], b.inputs["Alpha"])
    if emit > 0:
        nt.links.new(col, b.inputs["Emission Color"])
        if emit_mask:
            # Only the bright texels glow (lamps in a frame, ore specks, flames).
            bw = nt.nodes.new("ShaderNodeRGBToBW"); nt.links.new(t.outputs["Color"], bw.inputs["Color"])
            ramp = nt.nodes.new("ShaderNodeMapRange"); ramp.inputs["From Min"].default_value = 0.55; ramp.inputs["From Max"].default_value = 0.85
            nt.links.new(bw.outputs["Val"], ramp.inputs["Value"])
            mul = nt.nodes.new("ShaderNodeMath"); mul.operation = "MULTIPLY"; mul.inputs[1].default_value = emit
            nt.links.new(ramp.outputs["Result"], mul.inputs[0]); nt.links.new(mul.outputs[0], b.inputs["Emission Strength"])
        else:
            b.inputs["Emission Strength"].default_value = emit
    MATS[key] = m
    return m


def srgb(rgb):
    return tuple(((rgb >> s) & 255) / 255.0 for s in (16, 8, 0))


def lin(rgb):
    return tuple(c ** 2.2 for c in srgb(rgb))


def color_mat(name, rgb, emit=0.0, rough=0.5, transmission=0.0, ior=1.33, alpha=1.0):
    m = bpy.data.materials.new(name); m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*lin(rgb), 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Transmission Weight"].default_value = transmission
    b.inputs["IOR"].default_value = ior
    b.inputs["Alpha"].default_value = alpha
    if emit:
        b.inputs["Emission Color"].default_value = (*lin(rgb), 1)
        b.inputs["Emission Strength"].default_value = emit
    return m


# ------------------------------------------------------------------ voxel world


class B:
    """A full block: textures for top / sides / bottom (+ options)."""

    def __init__(self, top, side=None, bottom=None, tint_top=None, tint_side=None, emit=0.0, emit_mask=False, alpha=False,
                 rough=0.9, metal=0.0, solid=True, front=None):
        self.top, self.side, self.bottom = top, side or top, bottom or (side or top)
        self.front = front
        self.tint_top, self.tint_side = tint_top, tint_side
        self.emit, self.emit_mask, self.alpha, self.rough, self.metal = emit, emit_mask, alpha, rough, metal
        self.solid = solid and not alpha


WORLD = {}


def put(x, y, z, b):
    WORLD[(x, y, z)] = b


def fill(x0, x1, y0, y1, z0, z1, b):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                put(x, y, z, b)


FACES = {  # direction: (normal, 4 corners (unit cube), which texture)
    (0, 0, 1): ([(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)], "top"),
    (0, 0, -1): ([(0, 1, 0), (1, 1, 0), (1, 0, 0), (0, 0, 0)], "bottom"),
    (0, -1, 0): ([(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)], "side"),
    (0, 1, 0): ([(1, 1, 0), (0, 1, 0), (0, 1, 1), (1, 1, 1)], "side"),
    (-1, 0, 0): ([(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)], "side"),
    (1, 0, 0): ([(1, 0, 0), (1, 1, 0), (1, 1, 1), (1, 0, 1)], "side"),
}


def build_world():
    """One mesh per material, faces between two solid blocks left out."""
    groups = {}
    for (x, y, z), b in WORLD.items():
        for d, (corners, which) in FACES.items():
            n = WORLD.get((x + d[0], y + d[1], z + d[2]))
            if n is not None and n.solid and (b.solid or n is b):
                continue
            if n is b and not b.solid:
                continue
            name = getattr(b, which)
            tint = b.tint_top if which == "top" else b.tint_side
            if which == "side" and d == (0, -1, 0) and b.front:
                name = b.front
            m = mat(name, tint=tint, emit=b.emit, alpha=b.alpha, rough=b.rough, metal=b.metal, emit_mask=b.emit_mask)
            groups.setdefault(m.name, (m, name, []))[2].append(((x, y, z), corners, d))
    for key, (m, name, faces) in groups.items():
        fs = frame_scale(name)
        verts, polys, uvs = [], [], []
        for (x, y, z), corners, d in faces:
            base = len(verts)
            verts += [(x + c[0], y + c[1], z + c[2]) for c in corners]
            polys.append([base, base + 1, base + 2, base + 3])
            if d[2] != 0:
                uvs.append([(0, 1 - fs), (1, 1 - fs), (1, 1), (0, 1)] if d[2] > 0 else [(0, 1), (1, 1), (1, 1 - fs), (0, 1 - fs)])
            else:
                uvs.append([(0, 1 - fs), (1, 1 - fs), (1, 1), (0, 1)])
        me = bpy.data.meshes.new(key)
        me.from_pydata(verts, [], polys)
        layer = me.uv_layers.new()
        for p, uv in zip(me.polygons, uvs):
            for k, li in enumerate(p.loop_indices):
                layer.data[li].uv = uv[k]
        me.materials.append(m)
        o = bpy.data.objects.new(key, me)
        sc.collection.objects.link(o)


def quad(name, corners, m, uv=((0, 0), (1, 0), (1, 1), (0, 1))):
    me = bpy.data.meshes.new(name)
    me.from_pydata(corners, [], [[0, 1, 2, 3]])
    layer = me.uv_layers.new()
    for k, li in enumerate(me.polygons[0].loop_indices):
        layer.data[li].uv = uv[k]
    me.materials.append(m)
    o = bpy.data.objects.new(name, me)
    sc.collection.objects.link(o)
    return o


def cross(x, y, z, texname, tint=None, scale=1.0, emit=0.0, height=1.0):
    """A plant: two crossed cards in a block (x, y, z = block corner)."""
    m = mat(texname, tint=tint, alpha=True, emit=emit, emit_mask=emit > 0)
    fs = frame_scale(texname)
    cx, cy = x + 0.5 + random.uniform(-0.15, 0.15), y + 0.5 + random.uniform(-0.15, 0.15)
    r = 0.5 * scale
    h = height * scale
    uv = ((0, 1 - fs), (1, 1 - fs), (1, 1), (0, 1))
    for a in (45, 135):
        ca, sa = math.cos(math.radians(a)) * r, math.sin(math.radians(a)) * r
        quad("plant", [(cx - ca, cy - sa, z), (cx + ca, cy + sa, z), (cx + ca, cy + sa, z + h), (cx - ca, cy - sa, z + h)], m, uv)


def item(texname, loc, rot=(0, 0, 0), size=1.0, emit=0.0, thickness=1.0 / 16, metal=0.0, rough=0.55):
    """A held-item style 3D item: the sprite extruded by one pixel, side walls coloured by their own texel."""
    img = image(texname)
    w, h = img.size
    px = list(img.pixels)
    opaque = lambda i, j: 0 <= i < w and 0 <= j < h and px[(j * w + i) * 4 + 3] > 0.1
    m = mat(texname, alpha=True, emit=emit, emit_mask=emit > 0, metal=metal, rough=rough)
    verts, polys, uvs = [], [], []
    s = size / w
    t = thickness * size

    def add(vs, uv):
        base = len(verts)
        verts.extend(vs)
        polys.append([base, base + 1, base + 2, base + 3])
        uvs.append(uv)

    # front and back: the whole sprite (alpha cuts it out)
    add([(0, -t / 2, 0), (size, -t / 2, 0), (size, -t / 2, size), (0, -t / 2, size)], [(0, 0), (1, 0), (1, 1), (0, 1)])
    add([(size, t / 2, 0), (0, t / 2, 0), (0, t / 2, size), (size, t / 2, size)], [(1, 0), (0, 0), (0, 1), (1, 1)])
    for j in range(h):
        for i in range(w):
            if not opaque(i, j):
                continue
            u0, v0 = (i + 0.25) / w, (j + 0.25) / h
            u1, v1 = (i + 0.75) / w, (j + 0.75) / h
            uv = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            x0, x1, z0, z1 = i * s, (i + 1) * s, j * s, (j + 1) * s
            if not opaque(i - 1, j):
                add([(x0, t / 2, z0), (x0, -t / 2, z0), (x0, -t / 2, z1), (x0, t / 2, z1)], uv)
            if not opaque(i + 1, j):
                add([(x1, -t / 2, z0), (x1, t / 2, z0), (x1, t / 2, z1), (x1, -t / 2, z1)], uv)
            if not opaque(i, j - 1):
                add([(x0, -t / 2, z0), (x0, t / 2, z0), (x1, t / 2, z0), (x1, -t / 2, z0)], uv)
            if not opaque(i, j + 1):
                add([(x0, t / 2, z1), (x0, -t / 2, z1), (x1, -t / 2, z1), (x1, t / 2, z1)], uv)
    me = bpy.data.meshes.new(texname)
    me.from_pydata(verts, [], polys)
    layer = me.uv_layers.new()
    for p, uv in zip(me.polygons, uvs):
        for k, li in enumerate(p.loop_indices):
            layer.data[li].uv = uv[k]
    me.materials.append(m)
    o = bpy.data.objects.new(texname, me)
    sc.collection.objects.link(o)
    for v in me.vertices:  # pivot at the centre
        v.co -= Vector((size / 2, 0, size / 2))
    o.location = loc
    o.rotation_euler = tuple(math.radians(a) for a in rot)
    return o


def water_plane(x0, x1, y0, y1, z, rgb=0x3F76E4, ripple=0.25):
    """A real water surface: glass-like with small waves, tinted by depth."""
    m = bpy.data.materials.new("water"); m.use_nodes = True
    nt = m.node_tree; b = nt.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*lin(rgb), 1)
    b.inputs["Roughness"].default_value = 0.04
    b.inputs["Transmission Weight"].default_value = 0.92
    b.inputs["IOR"].default_value = 1.33
    noise = nt.nodes.new("ShaderNodeTexNoise"); noise.inputs["Scale"].default_value = 2.6; noise.inputs["Detail"].default_value = 6
    bump = nt.nodes.new("ShaderNodeBump"); bump.inputs["Strength"].default_value = ripple
    nt.links.new(noise.outputs["Fac"], bump.inputs["Height"]); nt.links.new(bump.outputs["Normal"], b.inputs["Normal"])
    quad("water", [(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)], m)
    # the water body: absorbs light with depth (blue-green deep water)
    vm = bpy.data.materials.new("waterbody"); vm.use_nodes = True
    vn = vm.node_tree.nodes; vn.remove(vn["Principled BSDF"])
    absorb = vn.new("ShaderNodeVolumeAbsorption"); absorb.inputs["Color"].default_value = (*lin(rgb), 1); absorb.inputs["Density"].default_value = 0.35
    vm.node_tree.links.new(absorb.outputs["Volume"], vn["Material Output"].inputs["Volume"])
    bpy.ops.mesh.primitive_cube_add(size=1, location=((x0 + x1) / 2, (y0 + y1) / 2, z - 4))
    cube = bpy.context.active_object; cube.scale = (x1 - x0, y1 - y0, 8); cube.data.materials.append(vm)


# ------------------------------------------------------------------ light, sky, camera, post


def sun(elev, azim, strength, rgb=0xFFE7C2, angle=1.2):
    L = bpy.data.lights.new("sun", "SUN"); L.energy = strength; L.color = srgb(rgb); L.angle = math.radians(angle)
    o = bpy.data.objects.new("sun", L); sc.collection.objects.link(o)
    o.rotation_euler = (math.radians(90 - elev), 0, math.radians(azim))
    return o


def point(loc, rgb, watts, radius=0.15):
    L = bpy.data.lights.new("lamp", "POINT"); L.energy = watts; L.color = srgb(rgb); L.shadow_soft_size = radius
    o = bpy.data.objects.new("lamp", L); sc.collection.objects.link(o); o.location = loc
    return o


def area(loc, target, rgb, watts, size=4):
    L = bpy.data.lights.new("area", "AREA"); L.energy = watts; L.color = srgb(rgb); L.size = size
    o = bpy.data.objects.new("area", L); sc.collection.objects.link(o); o.location = loc
    o.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    return o


def sky_gradient(zenith, horizon, ground=None, strength=1.0, haze=0.0, haze_rgb=0xFFFFFF, stars=False, sun_dir=None, sun_glow=None):
    """A painted sky (what the camera sees) that also lights the scene softly."""
    w = bpy.data.worlds.new("w"); w.use_nodes = True; sc.world = w
    nt = w.node_tree; n = nt.nodes; l = nt.links
    bg = n["Background"]
    tc = n.new("ShaderNodeTexCoord")
    sep = n.new("ShaderNodeSeparateXYZ"); l.new(tc.outputs["Generated"], sep.inputs[0])
    norm = n.new("ShaderNodeVectorMath"); norm.operation = "NORMALIZE"; l.new(tc.outputs["Generated"], norm.inputs[0])
    sep2 = n.new("ShaderNodeSeparateXYZ"); l.new(norm.outputs["Vector"], sep2.inputs[0])
    ramp = n.new("ShaderNodeValToRGB")
    els = ramp.color_ramp.elements
    els[0].position = 0.0; els[0].color = (*lin(ground if ground is not None else horizon), 1)
    els[1].position = 0.5; els[1].color = (*lin(horizon), 1)
    e = els.new(0.78); e.color = (*lin(zenith), 1)
    mr = n.new("ShaderNodeMapRange"); mr.inputs["From Min"].default_value = -1; mr.inputs["From Max"].default_value = 1
    l.new(sep2.outputs["Z"], mr.inputs["Value"]); l.new(mr.outputs["Result"], ramp.inputs["Fac"])
    col = ramp.outputs["Color"]
    if sun_dir and sun_glow:
        dot = n.new("ShaderNodeVectorMath"); dot.operation = "DOT_PRODUCT"; dot.inputs[1].default_value = Vector(sun_dir).normalized()
        l.new(norm.outputs["Vector"], dot.inputs[0])
        pw = n.new("ShaderNodeMath"); pw.operation = "POWER"; pw.inputs[1].default_value = 24
        cl = n.new("ShaderNodeMath"); cl.operation = "MAXIMUM"; cl.inputs[1].default_value = 0
        l.new(dot.outputs["Value"], cl.inputs[0]); l.new(cl.outputs[0], pw.inputs[0])
        add = n.new("ShaderNodeMix"); add.data_type = "RGBA"; add.blend_type = "ADD"
        l.new(pw.outputs[0], add.inputs["Factor"])
        l.new(col, [i for i in add.inputs if i.name == "A" and i.type == "RGBA"][0])
        [i for i in add.inputs if i.name == "B" and i.type == "RGBA"][0].default_value = (*lin(sun_glow), 1)
        col = [o for o in add.outputs if o.name == "Result" and o.type == "RGBA"][0]
    if stars:
        vor = n.new("ShaderNodeTexVoronoi"); vor.feature = "DISTANCE_TO_EDGE" if False else "F1"; vor.inputs["Scale"].default_value = 260
        l.new(norm.outputs["Vector"], vor.inputs["Vector"])
        st = n.new("ShaderNodeMapRange"); st.inputs["From Min"].default_value = 0.0; st.inputs["From Max"].default_value = 0.035
        st.inputs["To Min"].default_value = 3.0; st.inputs["To Max"].default_value = 0.0; st.clamp = True
        l.new(vor.outputs["Distance"], st.inputs["Value"])
        thr = n.new("ShaderNodeMath"); thr.operation = "GREATER_THAN"; thr.inputs[1].default_value = 0.65
        l.new(vor.outputs["Color"], thr.inputs[0])
        mm = n.new("ShaderNodeMath"); mm.operation = "MULTIPLY"; l.new(st.outputs["Result"], mm.inputs[0]); l.new(thr.outputs[0], mm.inputs[1])
        add2 = n.new("ShaderNodeMix"); add2.data_type = "RGBA"; add2.blend_type = "ADD"
        l.new(mm.outputs[0], add2.inputs["Factor"])
        l.new(col, [i for i in add2.inputs if i.name == "A" and i.type == "RGBA"][0])
        [i for i in add2.inputs if i.name == "B" and i.type == "RGBA"][0].default_value = (1, 1, 1, 1)
        col = [o for o in add2.outputs if o.name == "Result" and o.type == "RGBA"][0]
    l.new(col, bg.inputs["Color"]); bg.inputs["Strength"].default_value = strength
    if haze > 0:
        vol = n.new("ShaderNodeVolumePrincipled")
        vol.inputs["Density"].default_value = haze
        vol.inputs["Anisotropy"].default_value = 0.6
        vol.inputs["Color"].default_value = (*srgb(haze_rgb), 1)
        l.new(vol.outputs["Volume"], n["World Output"].inputs["Volume"])


def haze_box(center, size, density, rgb=0xFFFFFF, anisotropy=0.5, emit=0.0, emit_rgb=None):
    """Local fog (god rays, dust) in a box instead of the whole world."""
    bpy.ops.mesh.primitive_cube_add(size=1, location=center)
    o = bpy.context.active_object; o.scale = size; o.name = "haze"
    m = bpy.data.materials.new("haze"); m.use_nodes = True
    n = m.node_tree.nodes; n.remove(n["Principled BSDF"])
    v = n.new("ShaderNodeVolumePrincipled")
    v.inputs["Density"].default_value = density; v.inputs["Anisotropy"].default_value = anisotropy
    v.inputs["Color"].default_value = (*srgb(rgb), 1)
    if emit:
        v.inputs["Emission Strength"].default_value = emit
        v.inputs["Emission Color"].default_value = (*lin(emit_rgb or rgb), 1)
    m.node_tree.links.new(v.outputs["Volume"], n["Material Output"].inputs["Volume"])
    o.data.materials.append(m)
    o.visible_shadow = True
    return o


def camera(loc, target, lens=35, fstop=4.0, shift=(0, 0)):
    cd = bpy.data.cameras.new("cam"); cd.lens = lens; cd.sensor_width = 36
    cd.dof.use_dof = True; cd.dof.aperture_fstop = fstop
    cd.shift_x, cd.shift_y = shift
    cam = bpy.data.objects.new("cam", cd); sc.collection.objects.link(cam); sc.camera = cam
    cam.location = loc
    cam.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    cd.dof.focus_distance = (Vector(target) - Vector(loc)).length
    return cam


def post(bloom=0.6, threshold=0.9, size=8, exposure=0.0):
    sc.view_settings.exposure = exposure
    sc.use_nodes = True
    nt = sc.node_tree
    for node in list(nt.nodes):
        nt.nodes.remove(node)
    rl = nt.nodes.new("CompositorNodeRLayers")
    g = nt.nodes.new("CompositorNodeGlare")
    try:
        g.glare_type = "BLOOM"
    except TypeError:
        g.glare_type = "FOG_GLOW"
    g.quality = "HIGH"
    for name, value in (("Threshold", threshold), ("Strength", bloom), ("Size", size / 10)):
        if name in g.inputs:
            g.inputs[name].default_value = value
    if hasattr(g, "threshold"):
        g.threshold = threshold
    if hasattr(g, "mix"):
        g.mix = -1 + bloom
    if hasattr(g, "size") and isinstance(getattr(g, "size"), int):
        g.size = size
    comp = nt.nodes.new("CompositorNodeComposite")
    nt.links.new(rl.outputs["Image"], g.inputs["Image"])
    nt.links.new(g.outputs["Image"], comp.inputs["Image"])


# ------------------------------------------------------------------ helpers for terrain


def noise2(x, y, seed=0):
    def h(i, j):
        n = (i * 374761393 + j * 668265263 + seed * 982451653) & 0xFFFFFFFF
        n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
        return (n & 0xFFFF) / 65535.0
    xi, yi = math.floor(x), math.floor(y)
    fx, fy = x - xi, y - yi
    sx, sy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b, c, d = h(xi, yi), h(xi + 1, yi), h(xi, yi + 1), h(xi + 1, yi + 1)
    return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy


def fbm(x, y, seed=0, oct=3):
    v, amp, f, tot = 0, 1, 1, 0
    for k in range(oct):
        v += noise2(x * f, y * f, seed + k) * amp; tot += amp; amp *= 0.5; f *= 2
    return v / tot


def island(cx, cy, rx, ry, depth, top_block, under, core, seed=1, height=0, rough=1.2):
    """A floating island: grass/top layer, dirt under it, a tapering stone underside. Returns top z per (x, y)."""
    tops = {}
    for x in range(int(cx - rx - 2), int(cx + rx + 3)):
        for y in range(int(cy - ry - 2), int(cy + ry + 3)):
            d = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2
            edge = 1 - d + (fbm(x * 0.35, y * 0.35, seed) - 0.5) * 0.5
            if edge <= 0:
                continue
            z_top = height + int(round((fbm(x * 0.2, y * 0.2, seed + 9) - 0.5) * rough))
            bottom = z_top - 1 - int(edge * depth + fbm(x * 0.5, y * 0.5, seed + 3) * 2)
            for z in range(bottom, z_top + 1):
                put(x, y, z, top_block if z == z_top else (under if z >= z_top - 2 else core))
            tops[(x, y)] = z_top
    return tops


def oak(x, y, z, log, leaves, height=4, radius=2):
    for k in range(height):
        put(x, y, z + k, log)
    top = z + height
    for dx in range(-radius, radius + 1):
        for dy in range(-radius, radius + 1):
            for dz in range(-1, 2):
                if abs(dx) + abs(dy) + max(0, dz) * 2 <= radius + 1 and (dx, dy) != (0, 0) or dz >= 0 and abs(dx) + abs(dy) <= 1:
                    if random.random() > 0.12 or abs(dx) + abs(dy) < radius:
                        if (x + dx, y + dy, top + dz) not in WORLD:
                            put(x + dx, y + dy, top + dz, leaves)
    put(x, y, top + 1, leaves)


def clouds(z, seed, rgb=0xFFFFFF, cover=0.55, extent=60, emit=0.0):
    """Flat cloud slabs like the game's (fancy clouds): 12x12-block cells, 4 high, soft white."""
    m = color_mat("cloud", rgb, rough=1.0, emit=emit)
    for cx in range(-extent, extent, 12):
        for cy in range(-extent // 3, extent * 2, 12):
            if fbm(cx * 0.05, cy * 0.05, seed) < cover:
                continue
            bpy.ops.mesh.primitive_cube_add(size=1, location=(cx + 6, cy + 6, z))
            o = bpy.context.active_object; o.scale = (12, 12, 2.5); o.data.materials.append(m)


# ================================================================== scenes


def scene_189():
    """1.8.9 - classic PvP: a sky island at golden hour, a diamond sword in the grass, golden apple, ender pearl."""
    G = 0x91BD59
    grass = B("blocks/grass_top", "blocks/grass_side", "blocks/dirt", tint_top=G)
    dirt, stone = B("blocks/dirt"), B("blocks/stone")
    tops = island(0, 0, 7.5, 5.5, 6, grass, dirt, stone, seed=189)
    oak(-3, 2, tops.get((-3, 2), 0) + 1, B("blocks/log_oak_top", "blocks/log_oak"), B("blocks/leaves_oak", tint_top=0x48B518, tint_side=0x48B518, alpha=True), 4, 2)
    for (x, y), z in tops.items():
        r = random.random()
        if (x, y) in ((-3, 2), (1, -1), (2, -1), (0, -1)):
            continue
        if r < 0.22:
            cross(x, y, z + 1, "blocks/tallgrass", tint=G, scale=0.9)
        elif r < 0.27:
            cross(x, y, z + 1, random.choice(["blocks/flower_rose", "blocks/flower_dandelion", "blocks/flower_oxeye_daisy"]), scale=0.8)
    z0 = tops.get((1, -1), 0) + 1
    item("items/diamond_sword", (1.2, -1.6, z0 + 1.25), rot=(0, 135 + 10, 14), size=3.0, metal=0.2, rough=0.3)
    item("items/apple_golden", (-0.6, -1.9, z0 + 0.42), rot=(-8, 0, 30), size=0.9, emit=0.5)
    item("items/ender_pearl", (3.4, -2.2, z0 + 1.9), rot=(0, 0, -20), size=0.7, emit=0.8)
    # small islands far away
    build_world()
    sky_gradient(0x3E64B8, 0xF5A55A, ground=0xC98F67, strength=1.0, sun_dir=(0.55, 0.8, 0.12), sun_glow=0xFFD48A)
    sun(12, 145, 5.5, 0xFFC27A, angle=2.0)
    clouds(-16, 189, cover=0.62, extent=90)
    area((-14, -24, 14), (0, 0, 1), 0xBFD4FF, 2200, size=18)
    haze_box((0, 10, 0), (80, 60, 30), 0.0025, 0xFFE2B8)
    camera((-7.5, -17.5, 9.5), (0.6, 0.2, 1.2), lens=40, fstop=6.3)
    post(bloom=0.35, threshold=1.2)


def scene_194():
    """1.9.4 - the Combat Update's End: an end city corner of purpur, chorus plants, end rods, an elytra."""
    end = B("blocks/end_stone")
    purpur = B("blocks/purpur_block", rough=0.7)
    pillar = B("blocks/purpur_pillar_top", "blocks/purpur_pillar", rough=0.7)
    bricks = B("blocks/end_bricks")
    tops = island(0, 2, 9, 6, 5, end, end, end, seed=194, rough=0.6)
    # tower corner
    fill(2, 6, 3, 7, 1, 1, bricks)
    for z in range(2, 9):
        for (x, y) in ((2, 3), (6, 3), (2, 7), (6, 7)):
            put(x, y, z, pillar)
    for x in range(2, 7):
        for z in (4, 8):
            put(x, 3, z, purpur); put(x, 7, z, purpur)
    for y in range(3, 8):
        for z in (4, 8):
            put(2, y, z, purpur); put(6, y, z, purpur)
    fill(2, 6, 3, 7, 9, 9, purpur)
    # chorus plants
    chorus = B("blocks/chorus_plant", rough=0.8)
    flower = B("blocks/chorus_flower", emit=0.0)
    for (cx, cy, hh) in ((-4, 0, 5), (-6, 4, 3), (-2, 5, 4), (8, -1, 3)):
        zt = tops.get((cx, cy), 0)
        for k in range(1, hh + 1):
            put(cx, cy, zt + k, chorus)
        put(cx + 1, cy, zt + hh - 1, chorus); put(cx + 1, cy, zt + hh, flower)
        put(cx, cy, zt + hh + 1, flower)
    # end rods: thin glowing sticks (as items)
    for (x, y, z) in ((2, 3, 10), (6, 3, 10), (6, 7, 10), (0, -2, tops.get((0, -2), 0) + 1)):
        item("blocks/end_rod", (x + 0.5, y + 0.5, z + 0.5), rot=(0, 0, 0), size=1.0, emit=6.0, thickness=2 / 16)
        point((x + 0.5, y + 0.5, z + 1.0), 0xFFEFFF, 60, 0.2)
    item("items/elytra", (-1.4, -3.4, tops.get((-1, -3), 0) + 3.4), rot=(10, -10, 25), size=2.6, rough=0.6)
    point((-2.6, -5.0, tops.get((-1, -3), 0) + 4.4), 0xD9C8FF, 120, 0.5)
    build_world()
    sky_gradient(0x0B0716, 0x2A1440, ground=0x05030A, strength=0.6, stars=True)
    area((-10, -12, 14), (0, 2, 2), 0xB89CFF, 1800, size=10)
    area((12, 14, 10), (2, 4, 4), 0xFF7AD9, 900, size=8)
    haze_box((0, 4, 2), (60, 50, 20), 0.006, 0xC6A8FF, anisotropy=0.3)
    camera((-14.5, -16.0, 9.0), (1.5, 2.5, 4.6), lens=34, fstop=4.0)
    post(bloom=0.6, threshold=0.8)


def scene_1211():
    """1.21.1 - Tricky Trials: inside a trial chamber, tuff bricks, copper bulbs, a trial spawner and a vault, the mace."""
    tb = B("block/tuff_bricks")
    ct = B("block/chiseled_tuff_top", "block/chiseled_tuff")
    pt = B("block/polished_tuff")
    cu = B("block/waxed_cut_copper" if False else "block/cut_copper", rough=0.35, metal=0.6)
    oxi = B("block/oxidized_cut_copper", rough=0.6, metal=0.3)
    bulb = B("block/copper_bulb_lit", emit=3.5, emit_mask=True, rough=0.4, metal=0.5)
    grate = B("block/copper_grate", alpha=True, rough=0.4, metal=0.6)
    fill(-8, 8, -2, 12, 0, 0, pt)
    for x in range(-8, 9):
        for y in range(-2, 13):
            if (x + y) % 9 == 0 and abs(x) < 6 and 1 < y < 10:
                put(x, y, 0, cu)
    fill(-8, 8, 12, 12, 1, 8, tb)
    fill(-8, -8, -2, 12, 1, 8, tb)
    fill(8, 8, -2, 12, 1, 8, tb)
    for x in (-5, -1, 3):
        for z in (1, 2, 3, 4, 5, 6, 7):
            put(x, 12, z, ct if z in (1, 7) else tb)
    for x in range(-8, 9, 4):
        put(x, 11, 6, bulb); put(x, 11, 3, oxi)
    for y in range(0, 12, 4):
        put(-7, y, 6, bulb); put(7, y, 6, bulb)
    fill(-8, 8, -2, 12, 9, 9, tb)
    fill(-2, 2, 4, 8, 9, 9, grate)
    spawner = B("block/trial_spawner_top_active", "block/trial_spawner_side_active", "block/trial_spawner_bottom", emit=2.5, emit_mask=True, alpha=True, rough=0.5, metal=0.4)
    put(0, 6, 1, spawner)
    vault = B("block/vault_top", "block/vault_side_off", "block/vault_bottom", front="block/vault_front_on", emit=2.0, emit_mask=True, alpha=True, rough=0.5, metal=0.4)
    put(-4, 4, 1, vault); put(4, 4, 1, vault)
    item("item/mace", (0.5, 3.4, 3.6), rot=(0, 15, 32), size=1.8, metal=0.7, rough=0.3)
    item("item/heavy_core" if ("assets/minecraft/textures/item/heavy_core.png" in ZIP.namelist()) else "item/breeze_rod", (2.2, 2.4, 1.3), rot=(0, 0, 20), size=0.6, metal=0.6)
    for (x, y) in ((-4, 4), (4, 4), (0, 6)):
        point((x + 0.5, y + 0.5, 2.4), 0xFFA552, 120, 0.3)
    for x in range(-8, 9, 4):
        point((x + 0.5, 10.5, 6.5), 0xFFB25E, 90, 0.3)
    build_world()
    sky_gradient(0x0A0806, 0x1A120C, strength=0.2)
    area((0, -10, 8), (0, 5, 2), 0xFFD2A0, 900, size=6)
    haze_box((0, 5, 4), (17, 15, 9), 0.018, 0xFFD9B0, anisotropy=0.55)
    camera((-3.6, -6.8, 4.2), (0.5, 4.8, 2.2), lens=24, fstop=3.2)
    post(bloom=0.7, threshold=0.75)


def scene_12110():
    """1.21.10 - the Copper Age: copper ageing from new to oxidized, copper torches and lanterns, chains, a shelf."""
    stages = ["block/copper_block", "block/exposed_copper", "block/weathered_copper", "block/oxidized_copper"]
    blocks = [B(s, rough=0.35 - i * 0.05 + i * 0.15, metal=0.85 - i * 0.2) for i, s in enumerate(stages)]
    G = 0x79C05A
    grass = B("block/grass_block_top", "block/grass_block_side", "block/dirt", tint_top=G)
    stone = B("block/cobblestone")
    tops = island(0, 3, 9, 6, 4, grass, B("block/dirt"), stone, seed=1210, rough=0.8)
    # a stepped wall of copper, aging from left (new) to right (oxidized)
    for i in range(4):
        for j in range(2):
            x = -5 + i * 3 + j
            zt = tops.get((x, 5), 0)
            for z in range(zt + 1, zt + 2 + i + (j == 0)):
                put(x, 5, z, blocks[i])
                put(x, 6, z, blocks[i])
    rod = B("block/lightning_rod", rough=0.3, metal=0.9) if "assets/minecraft/textures/block/lightning_rod.png" in ZIP.namelist() else None
    if False and rod:
        item("block/lightning_rod", (5.5, 6.0, tops.get((5, 5), 0) + 5.6), rot=(0, 0, 0), size=1.0, metal=0.9, rough=0.25, thickness=2 / 16)
    # copper torches (green flame) and lanterns
    for (x, y) in ((-6, 2), (-1, 1), (4, 2), (7, 4)):
        zt = tops.get((x, y), 0)
        item("block/copper_torch", (x + 0.5, y + 0.5, zt + 1.75), rot=(0, 0, 25), size=1.5, emit=5.0, thickness=2 / 16)
        point((x + 0.5, y + 0.45, zt + 1.85), 0x9BFFC0, 25, 0.1)
    for (x, y, zz) in ((-3, 4, 5.4), (2, 4, 6.2)):
        item("item/copper_lantern", (x + 0.5, y + 0.6, tops.get((x, 5), 0) + zz), rot=(0, 0, 10), size=1.0, emit=3.0, thickness=4 / 16)
        for k in range(1, 4):
            item("item/copper_chain", (x + 0.5, y + 0.6, tops.get((x, 5), 0) + zz + 0.5 + k * 0.9), rot=(0, 0, 10 + 90 * (k % 2)), size=0.9, metal=0.8, rough=0.3, thickness=2 / 16)
        point((x + 0.5, y + 0.4, tops.get((x, 5), 0) + zz), 0xA8FFD0, 40, 0.15)
    for (x, y) in ((-4, 3), (1, 2), (6, 1)):
        zt = tops.get((x, y), 0)
        for name in ("block/short_grass",):
            cross(x, y, zt + 1, name, tint=G, scale=0.9)
    build_world()
    sky_gradient(0x6FA6D8, 0xE9D7B5, ground=0x8B9E7A, strength=1.1, sun_dir=(-0.6, 0.7, 0.35), sun_glow=0xFFF2D0)
    sun(30, -140, 4.2, 0xFFF0D8, angle=1.5)
    haze_box((0, 6, 2), (60, 40, 20), 0.0025, 0xF4F0E6)
    camera((-6.0, -12.5, 6.0), (0.3, 4.8, 2.6), lens=34, fstop=5.0)
    post(bloom=0.45, threshold=1.0)


def scene_12111():
    """1.21.11 - Mounts of Mayhem: spears stuck in the sand of a beach island at sunset, nautilus armour."""
    sand = B("block/sand")
    tops = island(0, 3, 8, 5, 3, sand, sand, B("block/sandstone_top", "block/sandstone", "block/sandstone_bottom"), seed=1211, rough=0.7, height=0)
    water_plane(-60, 60, -60, 80, -0.15, 0x2C6CB5, ripple=0.18)
    spears = ["item/diamond_spear", "item/golden_spear", "item/netherite_spear", "item/copper_spear", "item/iron_spear"]
    at = [(-3.5, 1.2, -18), (-0.2, 2.6, 8), (2.8, 1.0, 20), (5.6, 3.0, -6), (-6.4, 3.4, 12)]
    for name, (x, y, lean) in zip(spears, at):
        zt = tops.get((int(x), int(y)), 0)
        item(name, (x, y, zt + 1.55), rot=(0, 135 + lean, lean * 0.5), size=2.7, metal=0.5, rough=0.35)
    item("item/golden_nautilus_armor", (1.2, -0.3, tops.get((1, 0), 0) + 1.3), rot=(-14, 0, 18), size=1.2, metal=0.7, rough=0.3)
    item("item/nautilus_shell", (-1.8, -0.6, tops.get((-2, -1), 0) + 1.15), rot=(-20, 0, -25), size=0.8, rough=0.4)
    for (x, y) in ((-7, 5), (7, 6)):
        zt = tops.get((x, y), 0)
        item("item/copper_lantern" if "assets/minecraft/textures/item/copper_lantern.png" in ZIP.namelist() else "item/lantern",
             (x + 0.5, y + 0.5, zt + 1.4), size=0.9, emit=3.5, thickness=4 / 16)
        point((x + 0.5, y + 0.4, zt + 1.5), 0xFFB060, 60, 0.15)
    build_world()
    sky_gradient(0x23305E, 0xFF8A4A, ground=0x5A3A4A, strength=0.9, sun_dir=(0.75, 0.66, 0.08), sun_glow=0xFFC07A)
    sun(7, 132, 6.0, 0xFF9E5C, angle=2.5)
    area((-8, -22, 10), (0, 2, 1.5), 0xFFC9A0, 2600, size=14)
    haze_box((0, 20, 2), (120, 80, 16), 0.0025, 0xFFC9A0)
    camera((-4.5, -15.5, 7.2), (0.4, 2.2, 1.4), lens=40, fstop=5.6)
    post(bloom=0.5, threshold=1.0)


def scene_261():
    """26.1 - a meadow of the new golden dandelions around a gold ore outcrop."""
    G = 0x79C05A
    grass = B("block/grass_block_top", "block/grass_block_side", "block/dirt", tint_top=G)
    tops = island(0, 3, 11, 7, 5, grass, B("block/dirt"), B("block/stone"), seed=261, rough=1.6)
    gold = B("block/gold_ore", emit=0.9, emit_mask=True, rough=0.5, metal=0.4)
    for (x, y, h) in ((2, 5, 3), (3, 5, 2), (2, 6, 2), (1, 5, 1), (3, 6, 1), (4, 5, 1)):
        zt = tops.get((x, y), 0)
        for z in range(zt + 1, zt + 1 + h):
            put(x, y, z, gold)
    oak(-6, 6, tops.get((-6, 6), 0) + 1, B("block/oak_log_top", "block/oak_log"), B("block/oak_leaves", tint_top=0x59AE30, tint_side=0x59AE30, alpha=True), 5, 2)
    for (x, y), z in tops.items():
        if WORLD.get((x, y, z + 1)):
            continue
        r = random.random()
        if r < 0.3:
            cross(x, y, z + 1, "block/golden_dandelion", scale=0.85, emit=0.3)
        elif r < 0.45:
            cross(x, y, z + 1, "block/short_grass", tint=G, scale=0.9)
    build_world()
    sky_gradient(0x4F86D8, 0xF8E3B0, ground=0x9DB37A, strength=1.1, sun_dir=(0.4, 0.8, 0.4), sun_glow=0xFFF0C0)
    sun(24, 150, 4.8, 0xFFE4B0, angle=1.6)
    haze_box((0, 6, 3), (70, 50, 22), 0.0025, 0xFFF0D0)
    camera((0.5, -16.5, 5.0), (0.8, 3.5, 2.0), lens=36, fstop=4.5)
    post(bloom=0.45, threshold=1.0)


def scene_262():
    """26.2 - a sulfur and cinnabar cave around a lava pool, crystals glowing."""
    names = set(ZIP.namelist())
    pick = lambda *c: next((n for n in c if "assets/minecraft/textures/" + n + ".png" in names), c[-1])
    sulfur = B(pick("block/sulfur", "block/raw_gold_block"), rough=0.7)
    cinnabar = B(pick("block/cinnabar", "block/red_terracotta"), rough=0.7)
    bricks = B(pick("block/cinnabar_bricks", "block/nether_bricks"))
    stone = B("block/deepslate")
    for x in range(-10, 11):
        for y in range(-2, 14):
            floor = int(round((fbm(x * 0.25, y * 0.25, 2620) - 0.5) * 2))
            ceil = 8 + int(round((fbm(x * 0.3, y * 0.3, 2621) - 0.5) * 3))
            for z in range(-3, floor + 1):
                put(x, y, z, sulfur if fbm(x * 0.4, y * 0.4 + z, 3) > 0.55 else (cinnabar if fbm(x * 0.5, z * 0.4 + y, 4) > 0.58 else stone))
            if y > 0 or abs(x) > 8:
                for z in range(ceil, 12):
                    put(x, y, z, stone if (x + y + z) % 5 else cinnabar)
        for z in range(-3, 12):
            pass
    for x in range(-10, 11):
        for z in range(-3, 12):
            put(x, 14, z, stone)
    lava = B("block/lava_still", emit=4.0, rough=0.6)
    for x in range(-3, 4):
        for y in range(4, 9):
            if (x / 3.5) ** 2 + ((y - 6.5) / 2.6) ** 2 < 1:
                for z in range(-3, 1):
                    WORLD.pop((x, y, z), None)
                put(x, y, -1, lava)
    for (x, y) in ((-6, 3), (-5, 9), (5, 2), (6, 8), (-2, 11), (3, 11)):
        zt = max(z for z in range(-3, 4) if (x, y, z) in WORLD) if any((x, y, z) in WORLD for z in range(-3, 4)) else 0
        for k in range(1, random.randint(2, 4)):
            put(x, y, zt + k, sulfur)
    build_world()
    sky_gradient(0x050302, 0x0A0604, strength=0.1)
    point((0.5, 6.5, 0.6), 0xFF6A1E, 2600, 1.5)
    area((0, -10, 6), (0, 6, 2), 0xFFD69A, 500, size=5)
    haze_box((0, 6, 3), (21, 16, 10), 0.015, 0xFFB070, anisotropy=0.3)
    camera((-1.2, -5.5, 3.6), (0.2, 6.0, 0.4), lens=24, fstop=3.5)
    post(bloom=0.8, threshold=0.7)


def scene_263():
    """26.3 - an autumn poplar forest: orange, red and yellow poplar leaves, fallen leaves, shelf mushrooms."""
    G = 0x8DB360
    grass = B("block/grass_block_top", "block/grass_block_side", "block/dirt", tint_top=G)
    tops = island(0, 4, 12, 8, 4, grass, B("block/dirt"), B("block/stone"), seed=263, rough=1.0)
    log = B("block/poplar_log_top", "block/poplar_log")
    leaves = [B(n, alpha=True) for n in ("block/orange_poplar_leaves", "block/red_poplar_leaves", "block/yellow_poplar_leaves")]
    for (x, y, h) in ((-7, 6, 7), (-2, 8, 8), (4, 7, 6), (8, 4, 7), (1, 3, 5), (-9, 2, 5)):
        zt = tops.get((x, y), 0)
        for k in range(1, h + 1):
            put(x, y, zt + k, log)
        lf = random.choice(leaves)
        for dz in range(h - 3, h + 3):
            r = 1 if dz > h else 2 if dz > h - 3 else 1
            for dx in range(-r, r + 1):
                for dy in range(-r, r + 1):
                    if abs(dx) + abs(dy) <= r + 1 and (dx, dy) != (0, 0) or dz > h:
                        if random.random() > 0.15:
                            put(x + dx, y + dy, zt + dz, lf)
    for (x, y), z in tops.items():
        if WORLD.get((x, y, z + 1)):
            continue
        r = random.random()
        if r < 0.25 and "assets/minecraft/textures/block/red_shrub.png" in ZIP.namelist():
            cross(x, y, z + 1, "block/red_shrub", scale=0.8)
        elif r < 0.4:
            cross(x, y, z + 1, "block/short_grass", tint=0xA8B05A, scale=0.85)
    build_world()
    sky_gradient(0x8FA7C9, 0xF2C99A, ground=0xA0866A, strength=1.0, sun_dir=(-0.5, 0.8, 0.25), sun_glow=0xFFD9A0)
    sun(16, -150, 4.5, 0xFFD29A, angle=2.0)
    haze_box((0, 6, 4), (70, 50, 24), 0.004, 0xFFE2C0, anisotropy=0.7)
    camera((1.0, -15.5, 3.6), (0.4, 5.0, 3.6), lens=34, fstop=4.0)
    post(bloom=0.45, threshold=1.0)


SCENES = {"1.8.9": scene_189, "1.9.4": scene_194, "1.21.1": scene_1211, "1.21.10": scene_12110, "1.21.11": scene_12111,
          "26.1": scene_261, "26.2": scene_262, "26.3": scene_263}
SCENES[VERSION]()
sc.render.filepath = OUT
sc.render.image_settings.file_format = "PNG"
bpy.ops.render.render(write_still=True)
print("TILE DONE", VERSION, OUT)
