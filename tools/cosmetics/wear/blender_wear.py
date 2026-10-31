"""Bodywear models, built in Blender (headless) - run through make_wear.sh:

    blender -b --python tools/cosmetics/wear/blender_wear.py -- <id> [<id>...]

Each item is modelled here in skin pixels around its bone's pivot (Blender space: X = the player's left, Z up, -Y the
front; the head's pivot is the neck, so the head is the box X -4..4, Y -4..4, Z 0..8). Shapes are smoothed with bevel
and subdivision modifiers and given procedural materials, which are baked (with ambient occlusion) into one texture.
Out come, in server/cosmetics-seed/wear/:
    <id>.vprop / <id>_l.vprop   the mesh (feet: right and mirrored left), bone space: skin pixels, y down, face -z
    <id>.png                    its texture
    <id>_preview.png            the Store picture (Cycles, on a stand-in body)
"""
import math
import os
import struct
import sys
from pathlib import Path

import bmesh
import bpy
from mathutils import Matrix, Vector

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "server/cosmetics-seed/wear"
TEX = 1024
OUT_TEX = 512

# ---------------------------------------------------------------- scene helpers


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 48
    scene.unit_settings.system = "NONE"


def obj(mesh_obj, mat=None, bevel=0.0, segments=3, subsurf=0, smooth=True):
    if bevel > 0:
        m = mesh_obj.modifiers.new("bevel", "BEVEL")
        m.width = bevel
        m.segments = segments
        m.limit_method = "ANGLE"
    if subsurf:
        m = mesh_obj.modifiers.new("subsurf", "SUBSURF")
        m.levels = subsurf
        m.render_levels = subsurf
    if smooth:
        for p in mesh_obj.data.polygons:
            p.use_smooth = True
    if mat is not None:
        mesh_obj.data.materials.clear()
        mesh_obj.data.materials.append(mat)
    return mesh_obj


def cube(size, loc, rot=(0, 0, 0)):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc, rotation=[math.radians(a) for a in rot])
    o = bpy.context.active_object
    o.scale = size
    bpy.ops.object.transform_apply(scale=True, rotation=True)
    return o


def cylinder(r, depth, loc, rot=(0, 0, 0), verts=40, r2=None):
    if r2 is None:
        bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=depth, location=loc, rotation=[math.radians(a) for a in rot], vertices=verts)
    else:
        bpy.ops.mesh.primitive_cone_add(radius1=r, radius2=r2, depth=depth, location=loc, rotation=[math.radians(a) for a in rot], vertices=verts)
    o = bpy.context.active_object
    bpy.ops.object.transform_apply(rotation=True)
    return o


def sphere(r, loc, scale=(1, 1, 1), seg=24):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=r, location=loc, segments=seg, ring_count=seg // 2)
    o = bpy.context.active_object
    o.scale = scale
    bpy.ops.object.transform_apply(scale=True)
    return o


def torus(R, r, loc, rot=(0, 0, 0), major=48, minor=12):
    bpy.ops.mesh.primitive_torus_add(major_radius=R, minor_radius=r, location=loc, rotation=[math.radians(a) for a in rot],
                                     major_segments=major, minor_segments=minor)
    o = bpy.context.active_object
    bpy.ops.object.transform_apply(rotation=True)
    return o


def curve_tube(points, radius, mat=None, cyclic=False, flat=1.0, resolution=4, bevel_res=4):
    """A tube (frames, straps, bands) along points; ``flat`` < 1 squashes it into a strap."""
    data = bpy.data.curves.new("tube", "CURVE")
    data.dimensions = "3D"
    data.resolution_u = resolution
    data.bevel_depth = radius
    data.bevel_resolution = bevel_res
    spline = data.splines.new("POLY" if len(points) < 3 else "NURBS")
    spline.points.add(len(points) - 1)
    for p, co in zip(spline.points, points):
        p.co = (co[0], co[1], co[2], 1)
    spline.use_cyclic_u = cyclic
    spline.use_endpoint_u = not cyclic
    spline.order_u = min(4, len(points))
    o = bpy.data.objects.new("tube", data)
    bpy.context.collection.objects.link(o)
    bpy.context.view_layer.objects.active = o
    o.select_set(True)
    if flat != 1.0:
        data.bevel_mode = "ROUND"
        o.scale = (1, 1, 1)
    bpy.ops.object.convert(target="MESH")
    o = bpy.context.active_object
    if mat is not None:
        o.data.materials.append(mat)
    for p in o.data.polygons:
        p.use_smooth = True
    return o


def strap(points, width, thickness, mat=None, cyclic=False, normal_hint=(0, 0, 1)):
    """A flat strap along points: a rectangle swept with its width across ``normal_hint``."""
    verts, faces = [], []
    pts = [Vector(p) for p in points]
    n = len(pts)
    for i, p in enumerate(pts):
        t = (pts[(i + 1) % n] - pts[i - 1]) if cyclic else (pts[min(i + 1, n - 1)] - pts[max(i - 1, 0)])
        t.normalize()
        side = t.cross(Vector(normal_hint))
        if side.length < 1e-4:
            side = t.cross(Vector((1, 0, 0)))
        side.normalize()
        up = side.cross(t).normalized()
        for sx, sy in ((-width / 2, -thickness / 2), (width / 2, -thickness / 2), (width / 2, thickness / 2), (-width / 2, thickness / 2)):
            verts.append(p + side * sx + up * sy)
    rings = n if cyclic else n - 1
    for i in range(rings):
        a, b = i * 4, ((i + 1) % n) * 4
        for k in range(4):
            faces.append((a + k, a + (k + 1) % 4, b + (k + 1) % 4, b + k))
    if not cyclic:
        faces.append((0, 1, 2, 3))
        last = (n - 1) * 4
        faces.append((last + 3, last + 2, last + 1, last))
    mesh = bpy.data.meshes.new("strap")
    mesh.from_pydata([tuple(v) for v in verts], [], faces)
    o = bpy.data.objects.new("strap", mesh)
    bpy.context.collection.objects.link(o)
    if mat is not None:
        mesh.materials.append(mat)
    return o


def place(objs, rot_y=0.0, rot_x=0.0, offset=(0, 0, 0)):
    """Turns a group of finished objects about the origin (Y then X, degrees) and moves them - baking each one's own
    location in first, so the parts keep their places relative to each other."""
    m = Matrix.Translation(Vector(offset)) @ Matrix.Rotation(math.radians(rot_x), 4, "X") @ Matrix.Rotation(math.radians(rot_y), 4, "Y")
    for o in objs:
        o.data.transform(o.matrix_basis)
        o.matrix_basis = Matrix.Identity(4)
        o.data.transform(m)
    return objs


def plate(outline, thickness, mat=None, bevel=0.05, axis="Y"):
    """A flat shape from a closed outline (points in the plane across ``axis``), ``thickness`` deep."""
    bm = bmesh.new()
    vs = [bm.verts.new(p) for p in outline]
    face = bm.faces.new(vs)
    normal = Vector({"X": (1, 0, 0), "Y": (0, 1, 0), "Z": (0, 0, 1)}[axis])
    ext = bmesh.ops.extrude_face_region(bm, geom=[face])
    moved = [e for e in ext["geom"] if isinstance(e, bmesh.types.BMVert)]
    bmesh.ops.translate(bm, vec=normal * thickness, verts=moved)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    mesh = bpy.data.meshes.new("plate")
    bm.to_mesh(mesh)
    bm.free()
    o = bpy.data.objects.new("plate", mesh)
    bpy.context.collection.objects.link(o)
    bpy.context.view_layer.objects.active = o
    return obj(o, mat, bevel=bevel, segments=2)


def lathe(profile, mat=None, verts=48, loc=(0, 0, 0)):
    """A surface of revolution round Z: ``profile`` (radius, z) points."""
    bm = bmesh.new()
    rings = []
    for k in range(verts):
        a = 2 * math.pi * k / verts
        rings.append([bm.verts.new((r * math.cos(a) + loc[0], r * math.sin(a) + loc[1], z + loc[2])) for r, z in profile])
    for k in range(verts):
        a, b = rings[k], rings[(k + 1) % verts]
        for i in range(len(profile) - 1):
            bm.faces.new((a[i], b[i], b[i + 1], a[i + 1]))
    mesh = bpy.data.meshes.new("lathe")
    bm.to_mesh(mesh)
    bm.free()
    o = bpy.data.objects.new("lathe", mesh)
    bpy.context.collection.objects.link(o)
    bpy.context.view_layer.objects.active = o
    if mat is not None:
        mesh.materials.append(mat)
    for p in mesh.polygons:
        p.use_smooth = True
    return o


# ---------------------------------------------------------------- materials


def wool_material(name, color, stitch=1.6):
    """Knitted wool: columns of V-shaped stitches (stockinette), dark grooves between the columns, each stitch shaded
    round like a plump loop of yarn, and fine fuzz over it all - baked into the texture like every other material."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = 1.0
    base = hex_rgb(color)

    def math_node(op, a, b=None):
        n = nt.nodes.new("ShaderNodeMath")
        n.operation = op
        for idx, v in enumerate((a, b)):
            if v is None:
                continue
            if isinstance(v, (int, float)):
                n.inputs[idx].default_value = v
            else:
                nt.links.new(v, n.inputs[idx])
        return n.outputs[0]

    coord = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(coord.outputs["Object"], sep.inputs[0])
    # stitch columns run across the strip (x and y together, so a loop round the neck and a hanging end both get them)
    across = math_node("ADD", sep.outputs["X"], sep.outputs["Y"])
    u = math_node("MULTIPLY", across, stitch)
    v = math_node("MULTIPLY", sep.outputs["Z"], stitch * 1.5)
    cu = math_node("SUBTRACT", math_node("FRACT", u), 0.5)  # -0.5..0.5 across a column
    acu = math_node("ABSOLUTE", cu)
    # a V: rows shifted up towards the column's edges
    chev = math_node("FRACT", math_node("ADD", v, math_node("MULTIPLY", acu, 1.1)))
    loop = math_node("SUBTRACT", 1.0, math_node("ABSOLUTE", math_node("MULTIPLY", math_node("SUBTRACT", chev, 0.5), 2.0)))  # 1 mid-stitch
    loop = math_node("POWER", loop, 0.6)
    groove = math_node("SUBTRACT", 1.0, math_node("POWER", math_node("MULTIPLY", acu, 2.0), 6.0))  # dark at the column edges
    shade = math_node("MULTIPLY", loop, groove)
    fuzz = nt.nodes.new("ShaderNodeTexNoise")
    fuzz.inputs["Scale"].default_value = 140
    fuzz.inputs["Detail"].default_value = 10
    nt.links.new(coord.outputs["Object"], fuzz.inputs["Vector"])
    shade = math_node("ADD", math_node("MULTIPLY", shade, 0.75), math_node("MULTIPLY", fuzz.outputs["Fac"], 0.35))
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.15
    ramp.color_ramp.elements[0].color = tuple(c * 0.5 for c in base) + (1,)
    ramp.color_ramp.elements[1].position = 0.95
    ramp.color_ramp.elements[1].color = tuple(min(1, c * 1.12 + 0.03) for c in base) + (1,)
    nt.links.new(shade, ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    return m


def plush_material(name, color):
    """Soft plush / velvet (a Santa hat): very fine fibres, brushed one way, gentle light and dark - no visible pattern."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = 1.0
    base = hex_rgb(color)
    coord = nt.nodes.new("ShaderNodeTexCoord")
    # brushed fibres: noise stretched along Z
    stretch = nt.nodes.new("ShaderNodeMapping")
    stretch.inputs["Scale"].default_value = (1.0, 1.0, 0.18)
    nt.links.new(coord.outputs["Object"], stretch.inputs["Vector"])
    fibres = nt.nodes.new("ShaderNodeTexNoise")
    fibres.inputs["Scale"].default_value = 260
    fibres.inputs["Detail"].default_value = 6
    nt.links.new(stretch.outputs["Vector"], fibres.inputs["Vector"])
    # soft, large patches where the pile lies differently
    patches = nt.nodes.new("ShaderNodeTexNoise")
    patches.inputs["Scale"].default_value = 3.0
    patches.inputs["Detail"].default_value = 2
    nt.links.new(coord.outputs["Object"], patches.inputs["Vector"])
    mix = nt.nodes.new("ShaderNodeMath")
    mix.operation = "MULTIPLY_ADD"
    nt.links.new(fibres.outputs["Fac"], mix.inputs[0])
    mix.inputs[1].default_value = 0.55
    lift = nt.nodes.new("ShaderNodeMath")
    lift.operation = "MULTIPLY"
    nt.links.new(patches.outputs["Fac"], lift.inputs[0])
    lift.inputs[1].default_value = 0.45
    nt.links.new(lift.outputs[0], mix.inputs[2])
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.3
    ramp.color_ramp.elements[0].color = tuple(c * 0.9 for c in base) + (1,)
    ramp.color_ramp.elements[1].position = 0.75
    ramp.color_ramp.elements[1].color = tuple(min(1, c * 1.1 + 0.02) for c in base) + (1,)
    nt.links.new(mix.outputs[0], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    return m


def material(name, color, rough=0.5, metal=0.0, noise=0.0, noise_scale=8.0, stripes=None, knit=False, emission=None):
    """A procedural material: a base colour, optionally mottled by noise, striped or knitted (all baked later)."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = rough
    # Metallic materials bake no base colour: metals are painted (a brushed streak) instead.
    if metal and not (noise or stripes or knit):
        stripes, noise, noise_scale = "Z", 0.35, 14
    base = (*hex_rgb(color), 1)
    if noise or stripes or knit:
        tex = nt.nodes.new("ShaderNodeTexCoord")
        mix_in = None
        ramp = nt.nodes.new("ShaderNodeValToRGB")
        ramp.color_ramp.elements[0].color = tuple(min(1, c * (1 - noise)) for c in base[:3]) + (1,)
        ramp.color_ramp.elements[1].color = tuple(min(1, c * (1 + noise * 0.6)) for c in base[:3]) + (1,)
        if knit:
            wave = nt.nodes.new("ShaderNodeTexWave")
            wave.wave_type = "BANDS"
            wave.bands_direction = "Z"
            wave.inputs["Scale"].default_value = 3.5
            wave.inputs["Distortion"].default_value = 3.0
            nt.links.new(tex.outputs["Object"], wave.inputs["Vector"])
            mix_in = wave.outputs["Fac"]
        elif stripes:
            wave = nt.nodes.new("ShaderNodeTexWave")
            wave.wave_type = "BANDS"
            wave.bands_direction = stripes
            wave.inputs["Scale"].default_value = noise_scale
            nt.links.new(tex.outputs["Object"], wave.inputs["Vector"])
            mix_in = wave.outputs["Fac"]
        else:
            n = nt.nodes.new("ShaderNodeTexNoise")
            n.inputs["Scale"].default_value = noise_scale
            n.inputs["Detail"].default_value = 8
            nt.links.new(tex.outputs["Object"], n.inputs["Vector"])
            mix_in = n.outputs["Fac"]
        nt.links.new(mix_in, ramp.inputs["Fac"])
        nt.links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    else:
        bsdf.inputs["Base Color"].default_value = base
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*hex_rgb(emission), 1)
        bsdf.inputs["Emission Strength"].default_value = 2.0
    return m


PAINT_X0, PAINT_Z0, PAINT_SIZE = -4.8, -0.6, 10.0


def painted(name, colors, pick, tint_index=None, rough=0.3, res=640):
    """A material painted with ``colors[pick(x, z)]`` across the face (object x, z in pixels), smooth-edged - so markings
    don't follow the mesh's cells. ``tint_index``: the colour a wearer's pick recolours (its own mask image)."""
    m = material(name, colors[0], rough=rough)
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    lin = [(*hex_rgb(c), 1.0) for c in colors]
    px = []
    mask = []
    sub = ((0.25, 0.25), (0.75, 0.25), (0.25, 0.75), (0.75, 0.75))
    for j in range(res):
        for i in range(res):
            acc = [0.0, 0.0, 0.0]
            hit = 0.0
            for su, sv in sub:
                k = pick(PAINT_X0 + (i + su) / res * PAINT_SIZE, PAINT_Z0 + (j + sv) / res * PAINT_SIZE)
                for c in range(3):
                    acc[c] += lin[k][c] / 4
                hit += 0.25 if k == tint_index else 0.0
            px += [acc[0], acc[1], acc[2], 1.0]
            mask += [hit, hit, hit, 1.0]
    img = bpy.data.images.new(name + "_paint", res, res, float_buffer=True)
    img.pixels = px
    coords = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    comb = nt.nodes.new("ShaderNodeCombineXYZ")
    mapping = nt.nodes.new("ShaderNodeMapping")
    mapping.inputs["Location"].default_value = (-PAINT_X0 / PAINT_SIZE, -PAINT_Z0 / PAINT_SIZE, 0)
    mapping.inputs["Scale"].default_value = (1 / PAINT_SIZE, 1 / PAINT_SIZE, 1)
    node = nt.nodes.new("ShaderNodeTexImage")
    node.name = "Paint"
    node.image = img
    node.extension = "EXTEND"
    nt.links.new(coords.outputs["Object"], sep.inputs["Vector"])
    nt.links.new(sep.outputs["X"], comb.inputs["X"])
    nt.links.new(sep.outputs["Z"], comb.inputs["Y"])
    nt.links.new(comb.outputs["Vector"], mapping.inputs["Vector"])
    nt.links.new(mapping.outputs["Vector"], node.inputs["Vector"])
    nt.links.new(node.outputs["Color"], bsdf.inputs["Base Color"])
    if tint_index is not None:
        mimg = bpy.data.images.new(name + "_paintmask", res, res, float_buffer=True)
        mimg.pixels = mask
        m["paint_mask"] = mimg.name
    return m


def hex_rgb(h):
    """sRGB hex to linear (Blender colours are linear)."""
    def lin(c):
        c /= 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    return tuple(lin(int(h[i:i + 2], 16)) for i in (1, 3, 5))


# ---------------------------------------------------------------- the items

def teardrop(cx, cy, side, w=1.6, h=1.2, n=36, z0=4.9):
    """An aviator lens outline in the XZ plane at depth ``cy``: wider at the top, a drop towards the nose-bottom."""
    out = []
    for k in range(n):
        a = 2 * math.pi * k / n
        x, z = math.cos(a) * w, math.sin(a) * h
        if z < 0:
            z *= 1.3
            x += -side * z * 0.18
        out.append((cx + x, cy, z0 + z))
    return out


def rounded_square(half, r, z, n=6, cy=0.0):
    """A rounded-square loop round the leg/arm (XY plane at height z)."""
    pts = []
    for cx, cyy, a0 in ((half - r, half - r, 0), (-half + r, half - r, 90), (-half + r, -half + r, 180), (half - r, -half + r, 270)):
        for k in range(n + 1):
            a = math.radians(a0 + 90 * k / n)
            pts.append((cx + r * math.cos(a), cy + cyy + r * math.sin(a), z))
    return pts


# ================================================================ the items
# Bone frames (Blender units = skin pixels, from the bone's pivot; -Y is the front):
#   head      X -4..4, Y -4..4, Z 0..8       (the eyes are around Z 3.5)
#   body      X -4..4, Y -2..2, Z -12..0     (the back is Y +2)
#   leftArm   X -1..3, Y -2..2, Z -10..2     (outer side +X; slim arms are drawn 3 wide, the clients squeeze it)
#   legs      X -2..2, Y -2..2, Z -12..0     (the legs touch: a shoe may only grow outwards, forwards and back)


def mats(**colors):
    return {k: (material(k, v) if isinstance(v, str) else material(k, *v[:1], **v[1])) for k, v in colors.items()}


# ---- head (hats) ----

def item_top_hat():
    silk = material("silk", "#1c1c22", rough=0.35, noise=0.25, noise_scale=30)
    band = material("band", "#b3222f", rough=0.4, stripes="Z", noise=0.2, noise_scale=6)
    brim = lathe([(0.0, 8.15), (5.6, 8.15), (6.1, 8.35), (6.15, 8.55), (5.6, 8.75), (3.7, 8.85), (3.7, 8.7)], silk)
    crown = lathe([(3.55, 8.6), (3.65, 11.5), (3.85, 15.2), (3.8, 15.45), (3.5, 15.6), (0.0, 15.62)], silk)
    ribbon = lathe([(3.66, 8.8), (3.72, 10.4), (3.6, 10.45), (3.58, 8.8)], band)
    bow = obj(cube((1.0, 0.5, 1.2), (3.55, 0.0, 9.6)), band, bevel=0.15)
    return {"bone": "head", "objects": [brim, crown, ribbon, bow]}


def dome(w, d, h, z0, mat, front_drop=0.0, seg=48, square=0.3):
    """A soft cap on the head: a superellipsoid's top half - its cross-section a rounded square wide enough to hold the
    head's corners (they're 5.66 out), its sides steep - so no corner of the head ever pokes through."""
    def spow(x, e):
        return math.copysign(abs(x) ** e, x)
    bm = bmesh.new()
    rows, cols = 16, seg
    grid = []
    for r in range(rows + 1):
        phi = (math.pi / 2) * r / rows  # 0 at the rim, pi/2 at the top
        row = []
        for c in range(cols):
            th = 2 * math.pi * c / cols
            cp, sp = spow(math.cos(phi), 0.55), spow(math.sin(phi), 0.55)
            x = w / 2 * cp * spow(math.cos(th), square)
            y = d / 2 * cp * spow(math.sin(th), square)
            z = z0 + h * sp
            if front_drop and y < 0:
                z -= front_drop * (-y / (d / 2))
            row.append(bm.verts.new((x, y, z)))
        grid.append(row)
    # the top row has shrunk to one point: close it with a fan round a single vertex
    peak = bm.verts.new(grid[rows][0].co)
    for v in grid[rows]:
        bm.verts.remove(v)
    for r in range(rows):
        for c in range(cols):
            a, b = grid[r][c], grid[r][(c + 1) % cols]
            if r + 1 == rows:
                bm.faces.new((a, b, peak))
            else:
                bm.faces.new((a, b, grid[r + 1][(c + 1) % cols], grid[r + 1][c]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    mesh = bpy.data.meshes.new("dome")
    bm.to_mesh(mesh)
    bm.free()
    o = bpy.data.objects.new("dome", mesh)
    bpy.context.collection.objects.link(o)
    bpy.context.view_layer.objects.active = o
    solid = o.modifiers.new("solid", "SOLIDIFY")
    solid.thickness = 0.35
    return obj(o, mat, subsurf=1)


def item_snapback():
    cloth = material("cloth", "#26262e", rough=0.8, noise=0.15, noise_scale=60)
    brim_m = material("brim", "#e8473f", rough=0.6, noise=0.1, noise_scale=40)
    crown = dome(9.3, 9.3, 3.3, 6.85, cloth)
    brim = plate([(-4.0, -4.1, 7.2), (4.0, -4.1, 7.2), (4.2, -7.6, 7.0), (2.6, -8.8, 6.9), (-2.6, -8.8, 6.9), (-4.2, -7.6, 7.0)], 0.35, brim_m, bevel=0.15, axis="Z")
    button = obj(cylinder(0.55, 0.4, (0, 0, 10.1)), brim_m, bevel=0.15)
    patch = obj(cube((3.2, 0.25, 1.6), (0, -4.42, 8.4)), brim_m, bevel=0.12)
    strap_back = obj(cube((2.6, 0.3, 0.6), (0, 4.45, 7.3)), brim_m, bevel=0.1)
    return {"bone": "head", "objects": [crown, brim, button, patch, strap_back]}


def item_beanie():
    knit = wool_material("knit", "#e9a02b")
    cuff = wool_material("cuff", "#c27a12", stitch=2.0)
    fluff = material("fluff", "#f7f3ea", rough=1.0, noise=0.3, noise_scale=90)
    crown = dome(9.4, 9.4, 4.0, 6.5, knit)
    cuff_ring = obj(cube((9.7, 9.7, 1.6), (0, 0, 6.6)), cuff, bevel=0.6, segments=3, subsurf=1)
    pom = sphere(1.55, (0, 0, 11.0), seg=20)
    disp = pom.modifiers.new("disp", "DISPLACE")
    tex = bpy.data.textures.new("fluff", "CLOUDS")
    tex.noise_scale = 0.35
    disp.texture = tex
    disp.strength = 0.35
    obj(pom, fluff)
    return {"bone": "head", "objects": [crown, cuff_ring, pom]}


def bent_tube(path, radii, mat, seg=28, cap=True):
    """A tube through ``path`` (points), its radius per point (``radii``) - cones that bend, a scarf's tail. Closed at
    the end when ``cap``."""
    pts = [Vector(p) for p in path]
    bm = bmesh.new()
    rings = []
    prev_n = None
    for i, p in enumerate(pts):
        d = (pts[min(i + 1, len(pts) - 1)] - pts[max(i - 1, 0)]).normalized()
        # a frame that turns smoothly along the path
        n = prev_n if prev_n is not None else (Vector((1, 0, 0)) if abs(d.x) < 0.9 else Vector((0, 1, 0)))
        n = (n - d * n.dot(d)).normalized()
        prev_n = n
        b = d.cross(n)
        r = radii[i]
        if isinstance(r, (int, float)):
            r = (r, r)
        rings.append([bm.verts.new(p + n * (r[0] * math.cos(2 * math.pi * k / seg)) + b * (r[1] * math.sin(2 * math.pi * k / seg)))
                      for k in range(seg)])
    for a, c in zip(rings, rings[1:]):
        for k in range(seg):
            bm.faces.new((a[k], a[(k + 1) % seg], c[(k + 1) % seg], c[k]))
    if cap:
        bm.faces.new(rings[-1][::-1])
        bm.faces.new(rings[0])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    mesh = bpy.data.meshes.new("tube")
    bm.to_mesh(mesh)
    bm.free()
    o = bpy.data.objects.new("tube", mesh)
    bpy.context.collection.objects.link(o)
    bpy.context.view_layer.objects.active = o
    return obj(o, mat)


def fluffy(o, mat, strength=0.3, scale=0.35):
    disp = o.modifiers.new("disp", "DISPLACE")
    tex = bpy.data.textures.new("fluffn", "CLOUDS")
    tex.noise_scale = scale
    disp.texture = tex
    disp.strength = strength
    return obj(o, mat)


def item_witch_hat():
    felt = material("witchfelt", "#2b1f3a", rough=0.85, noise=0.3, noise_scale=40)
    band = material("witchband", "#7b3fc4", rough=0.5, stripes="Z", noise=0.2, noise_scale=6)
    gold = material("witchbuckle", "#e0b23c", metal=1.0)
    # A wide, slightly wavy brim.
    brim = lathe([(0.0, 8.1), (6.9, 8.05), (7.8, 8.35), (7.9, 8.55), (7.2, 8.45), (4.6, 8.5), (0, 8.5)], felt, verts=64)
    for v in brim.data.vertices:
        a = math.atan2(v.co.y, v.co.x)
        v.co.z += 0.25 * math.sin(3 * a) * min(1.0, math.hypot(v.co.x, v.co.y) / 7.8)
    # The cone: wide over the head, tapering up and bending back at the tip.
    path, radii = [], []
    for k in range(15):
        f = k / 14
        bend = f ** 2.4
        path.append((0.0, 3.6 * bend, 8.3 + 9.5 * f - 1.6 * bend))
        radii.append(4.5 * (1 - f) ** 1.25 + 0.12)
    cone = bent_tube(path, radii, felt)
    ribbon = lathe([(4.52, 8.45), (4.42, 9.9), (4.2, 9.95), (4.3, 8.45)], band, verts=56)
    buckle = obj(cube((1.6, 0.35, 1.3), (0, -4.5, 9.2)), gold, bevel=0.15, segments=2)
    hole = obj(cube((0.8, 0.4, 0.6), (0, -4.62, 9.2)), felt, bevel=0.05, segments=1)
    return {"bone": "head", "objects": [brim, cone, ribbon, buckle, hole], "view": "front"}


def item_santa_hat():
    red = plush_material("santared", "#de2a35")
    fur = material("santafur", "#f6f3ee", rough=1.0, noise=0.25, noise_scale=90)
    crown = dome(9.3, 9.3, 2.8, 7.0, red)
    # The floppy top: up from the crown, then flopping over to the left side and down.
    path, radii = [], []
    for k in range(16):
        f = k / 15
        a = f * math.pi * 0.85
        path.append((3.8 * (1 - math.cos(a)) * 0.9, 0.5 * f, 9.2 + 3.2 * math.sin(a) - 1.2 * f))
        radii.append(3.3 * (1 - f) ** 1.1 + 0.25)
    tip = bent_tube(path, radii, red)
    cuff = fluffy(obj(cube((9.9, 9.9, 1.9), (0, 0, 7.3)), None, bevel=0.85, segments=4, subsurf=2), fur, strength=0.22)
    pom = fluffy(sphere(1.25, (path[-1][0] + 0.2, path[-1][1], path[-1][2] - 0.9), seg=20), fur, strength=0.3)
    return {"bone": "head", "objects": [crown, tip, cuff, pom], "view": "front"}


def item_cowboy_hat():
    leather = material("leather", "#7a4a26", rough=0.7, noise=0.3, noise_scale=25)
    band = material("hatband", "#2a1b12", rough=0.6, stripes="Z", noise=0.25, noise_scale=12)
    brim = lathe([(0.0, 8.1), (6.6, 8.1), (7.6, 8.9), (7.75, 9.3), (7.2, 9.0), (6.0, 8.55), (4.2, 8.45), (0, 8.45)], leather, verts=56)
    brim.scale = (1.0, 1.18, 1.0)
    for v in brim.data.vertices:  # the sides curl up more than front and back
        side = abs(v.co.x) / 7.8
        v.co.z += 1.3 * side ** 3 * (1 if (v.co.x ** 2 + (v.co.y / 1.18) ** 2) > 30 else 0)
    obj(brim, leather)
    crown = dome(8.6, 9.0, 4.0, 8.3, leather)
    for v in crown.data.vertices:  # the pinch on top
        if v.co.z > 10.5:
            v.co.z -= 0.9 * max(0, 1 - abs(v.co.x) / 1.6)
    hatband = lathe([(3.75, 8.4), (3.8, 9.3), (3.7, 9.35), (3.65, 8.4)], band)
    hatband.scale = (1.0, 1.08, 1.0)
    return {"bone": "head", "objects": [brim, crown, hatband]}


def item_crown():
    gold = material("gold", "#f2c544", metal=1.0)
    ruby = material("ruby", "#d4183a", rough=0.12, noise=0.35, noise_scale=8)
    sapphire = material("sapphire", "#2756d6", rough=0.12, noise=0.35, noise_scale=8)
    velvet = material("velvet", "#7a0d1c", rough=1.0, noise=0.25, noise_scale=60)
    pearl = material("pearl", "#f4efe6", rough=0.2)
    # One clean ring of gold with eight points: outer and inner wall, the scalloped top edge and the bottom.
    n, points = 128, 8
    r_out, r_in, z_bot, z_band = 4.55, 4.25, 7.95, 9.5
    def top(a):
        f = (a * points / (2 * math.pi)) % 1.0
        peak = 1 - abs(f - 0.5) * 2  # 0 between points, 1 at a point
        return z_band + 2.1 * peak ** 1.6
    bm = bmesh.new()
    ring = []
    for k in range(n):
        a = 2 * math.pi * k / n
        c, s = math.cos(a), math.sin(a)
        zt = top(a + math.pi / points)
        ring.append([bm.verts.new((r_out * c, r_out * s, z_bot)), bm.verts.new((r_out * c, r_out * s, zt)),
                     bm.verts.new((r_in * c, r_in * s, zt)), bm.verts.new((r_in * c, r_in * s, z_bot))])
    for k in range(n):
        a, b = ring[k], ring[(k + 1) % n]
        for i in range(4):
            bm.faces.new((a[i], b[i], b[(i + 1) % 4], a[(i + 1) % 4]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    mesh = bpy.data.meshes.new("crownring")
    bm.to_mesh(mesh)
    bm.free()
    band = bpy.data.objects.new("crownring", mesh)
    bpy.context.collection.objects.link(band)
    obj(band, gold, bevel=0.06, segments=2)
    objs = [band]
    # Raised rims along the bottom and round the band.
    objs.append(obj(torus(4.6, 0.16, (0, 0, 8.0), major=64, minor=8), gold))
    objs.append(obj(torus(4.58, 0.12, (0, 0, z_band - 0.15), major=64, minor=8), gold))
    for k in range(points):
        a = 2 * math.pi * k / points
        c, s = math.cos(a), math.sin(a)
        # an orb on every point, a jewel between the rims under it
        objs.append(obj(sphere(0.38, (4.42 * c, 4.42 * s, z_band + 2.35), seg=14), pearl))
        gem = sphere(0.45, (4.68 * c, 4.68 * s, 8.75), scale=(1, 1, 1.25), seg=14)
        gem.rotation_euler = (0, 0, a)
        objs.append(obj(gem, ruby if k % 2 == 0 else sapphire))
        b = a + math.pi / points
        objs.append(obj(sphere(0.2, (4.66 * math.cos(b), 4.66 * math.sin(b), 8.75), seg=10), pearl))
    # Velvet cap inside.
    objs.append(dome(8.5, 8.5, 2.4, 7.95, velvet, square=1.0))
    return {"bone": "head", "objects": objs}


# ---- ears ----

def ear(mat, inner_mat, x, base_w, height, tilt, depth=0.9, inner_scale=0.6, round_tip=False, z0=8.0):
    """One ear standing on the head top at ``x``: a tapering wedge, its inside a softer colour, tilted round its base."""
    def outline(w, h):
        n = 10
        pts = []
        for k in range(n + 1):
            tt = k / n
            pts.append((-w / 2 + w * tt * 0.5, 0.0, h * tt))
        for k in range(n + 1):
            tt = 1 - k / n
            pts.append((w / 2 - w * tt * 0.5, 0.0, h * tt))
        if round_tip:
            pts = [(px * (1.0 if pz < h * 0.6 else 1.0 + 0.6 * ((pz - h * 0.6) / (h * 0.4))), py, pz) for px, py, pz in pts]
        return pts
    outer = plate(outline(base_w, height), depth, mat, bevel=0.25, axis="Y")
    inner = plate(outline(base_w * inner_scale, height * inner_scale + 0.2), 0.15, inner_mat, bevel=0.05, axis="Y")
    for v in inner.data.vertices:
        v.co.y -= 0.18
        v.co.z += 0.25
    for o in (outer, inner):
        for v in o.data.vertices:
            v.co.y -= depth / 2
        # Leaning outwards: a positive turn about +Y tips the top towards +X, so the left ear (side +1) takes -tilt.
        o.rotation_euler = (0, math.radians(-tilt), 0)
        # sunk by what the tilt lifts the inner base corner, so no gap opens under it
        o.location = (x, 0.0, z0 - 0.15 - base_w / 2 * math.sin(math.radians(abs(tilt))))
    return [obj(outer, mat, subsurf=1), inner]


def headband(mat, z_top=8.6, r=0.18):
    pts = [(4.15 * math.cos(math.radians(a)), 0.2, 4.6 + 4.1 * math.sin(math.radians(a))) for a in range(-10, 191, 10)]
    return curve_tube(pts, r, mat)


def item_cat_ears():
    fur = material("fur", "#23232a", rough=0.9, noise=0.3, noise_scale=70)
    pink = material("pink", "#ff9ccb", rough=0.8, noise=0.2, noise_scale=50)
    objs = []
    for side in (-1, 1):
        objs += ear(fur, pink, side * 2.4, 3.0, 3.1, -side * 12)
    return {"bone": "head", "objects": objs, "view": "front"}


def item_bunny_ears():
    fur = material("bunny", "#f6f4f0", rough=0.9, noise=0.15, noise_scale=70)
    pink = material("bunnypink", "#ffb6d3", rough=0.8, noise=0.2, noise_scale=50)
    objs = []
    for side in (-1, 1):
        objs += ear(fur, pink, side * 1.7, 2.0, 7.0, -side * 8, depth=0.8, round_tip=True)
    # one ear flops over at the tip
    return {"bone": "head", "objects": objs, "view": "front"}


def item_fox_ears():
    fur = material("foxfur", "#e8752a", rough=0.9, noise=0.3, noise_scale=70)
    cream = material("cream", "#fff1dd", rough=0.9, noise=0.2, noise_scale=60)
    tip = material("tip", "#2a1d17", rough=0.9)
    objs = []
    for side in (-1, 1):
        objs += ear(fur, cream, side * 2.3, 3.2, 4.0, -side * 18, depth=1.0)
    return {"bone": "head", "objects": objs, "view": "front"}


def item_devil_horns():
    red = material("horn", "#c01b23", rough=0.25, stripes="Z", noise=0.35, noise_scale=10)
    objs = []
    for side in (-1, 1):
        pts = [(side * (2.2 + 0.6 * t + 0.5 * t * t), -0.6 + 0.3 * t, 8.0 + 2.8 * t) for t in [k / 6 for k in range(7)]]
        horn = curve_tube(pts, 0.75, red)
        # taper towards the tip
        for v in horn.data.vertices:
            t = max(0.0, min(1.0, (v.co.z - 8.0) / 2.8))
            centre = Vector((side * (2.2 + 0.6 * t + 0.5 * t * t), -0.6 + 0.3 * t, v.co.z))
            v.co = centre + (v.co - centre) * (1 - 0.92 * t)
        objs.append(horn)
    return {"bone": "head", "objects": objs, "view": "front"}


def item_bear_ears():
    fur = material("bear", "#7b5232", rough=0.95, noise=0.3, noise_scale=70)
    inner = material("bearinner", "#d9a679", rough=0.9, noise=0.2, noise_scale=50)
    objs = []
    for side in (-1, 1):
        o = cylinder(1.5, 0.9, (side * 2.7, 0.1, 9.1), rot=(90, 0, 0), verts=32)
        objs.append(obj(o, fur, bevel=0.35, segments=3))
        i = cylinder(0.95, 0.2, (side * 2.7, -0.42, 9.0), rot=(90, 0, 0), verts=24)
        objs.append(obj(i, inner, bevel=0.06, segments=2))
    return {"bone": "head", "objects": objs, "view": "front"}


# ---- face (glasses) ----

EYE_Z = 2.8


def arms(mat, y0=-4.62, z=None, r=0.11):
    z = EYE_Z + 0.55 if z is None else z
    return [curve_tube([(side * 3.62, y0, z), (side * 4.2, y0 + 0.35, z), (side * 4.3, -2.0, z - 0.05),
                        (side * 4.3, 1.2, z - 0.15), (side * 4.27, 2.4, z - 0.9)], r, mat) for side in (-1, 1)]


def item_aviators():
    gold = material("gold", "#e2bd62", metal=1.0)
    lens = material("lens", "#1d2433", rough=0.1, stripes="Z", noise=0.7, noise_scale=1.4)
    objs = arms(gold)
    for side in (-1, 1):
        cx = side * 2.05
        objs.append(plate(teardrop(cx, -4.55, side, 1.5, 1.12, z0=EYE_Z), 0.14, lens, axis="Y"))
        objs.append(curve_tube(teardrop(cx, -4.62, side, z0=EYE_Z), 0.11, gold, cyclic=True))
    objs.append(curve_tube([(-0.5, -4.62, EYE_Z + 0.55), (0, -4.78, EYE_Z + 0.75), (0.5, -4.62, EYE_Z + 0.55)], 0.1, gold))
    objs.append(curve_tube([(-0.45, -4.62, EYE_Z + 1.1), (0.45, -4.62, EYE_Z + 1.1)], 0.08, gold))
    return {"bone": "head", "objects": objs, "view": "front"}


def ring(cx, cy, cz, rx, rz, n=32):
    return [(cx + rx * math.cos(2 * math.pi * k / n), cy, cz + rz * math.sin(2 * math.pi * k / n)) for k in range(n)]


def item_round_glasses():
    gold = material("wire", "#c99a3a", metal=1.0)
    glass = material("glass", "#cfe6ee", rough=0.05, stripes="Z", noise=0.25, noise_scale=1.5)
    objs = arms(gold, r=0.08)
    for side in (-1, 1):
        objs.append(curve_tube(ring(side * 1.95, -4.62, EYE_Z, 1.3, 1.3), 0.09, gold, cyclic=True))
        objs.append(plate(ring(side * 1.95, -4.55, EYE_Z, 1.22, 1.22), 0.08, glass, axis="Y"))
    objs.append(curve_tube([(-0.65, -4.62, EYE_Z + 0.3), (0, -4.8, EYE_Z + 0.6), (0.65, -4.62, EYE_Z + 0.3)], 0.08, gold))
    return {"bone": "head", "objects": objs, "view": "front"}


def item_cyber_visor():
    shell = material("shell", "#1a1d24", rough=0.35, noise=0.15, noise_scale=30)
    glow = material("glow", "#26f0ff", rough=0.1, stripes="X", noise=0.5, noise_scale=3, emission="#26f0ff")
    visor = obj(cube((9.0, 1.0, 2.2), (0, -4.5, EYE_Z)), shell, bevel=0.45, segments=4, subsurf=1)
    for v in visor.data.vertices:  # wraps round the face a little
        v.co.y += 0.12 * (v.co.x ** 2) / 4
    strip = obj(cube((7.4, 0.4, 0.8), (0, -5.05, EYE_Z)), glow, bevel=0.15, segments=2)
    for v in strip.data.vertices:
        v.co.y += 0.12 * (v.co.x ** 2) / 4
    side_pods = [obj(cube((0.8, 2.0, 1.6), (s * 4.45, -3.6, EYE_Z)), shell, bevel=0.3, segments=3) for s in (-1, 1)]
    band = curve_tube([(-4.4, -2.6, EYE_Z), (-4.45, 2.0, EYE_Z), (-3.0, 4.4, EYE_Z), (3.0, 4.4, EYE_Z), (4.45, 2.0, EYE_Z), (4.4, -2.6, EYE_Z)], 0.3, shell)
    return {"bone": "head", "objects": [visor, strip, band] + side_pods, "view": "front"}


def heart(cx, cy, cz, s, n=40):
    pts = []
    for k in range(n):
        t = 2 * math.pi * k / n
        x = 16 * math.sin(t) ** 3
        z = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((cx + x * s / 16, cy, cz + z * s / 16))
    return pts


def item_heart_glasses():
    frame = material("heartframe", "#ff4f9a", rough=0.3, noise=0.1, noise_scale=20)
    lens = material("heartlens", "#a3123f", rough=0.08, stripes="Z", noise=0.6, noise_scale=1.4)
    objs = arms(frame, r=0.13)
    for side in (-1, 1):
        objs.append(curve_tube(heart(side * 2.05, -4.64, EYE_Z, 1.55), 0.17, frame, cyclic=True))
        objs.append(plate(heart(side * 2.05, -4.55, EYE_Z, 1.42), 0.12, lens, axis="Y"))
    objs.append(curve_tube([(-0.55, -4.64, EYE_Z + 0.5), (0.55, -4.64, EYE_Z + 0.5)], 0.12, frame))
    return {"bone": "head", "objects": objs, "view": "front"}


def shade_lens(cx, cy, w=3.1, h=2.1, n=40):
    """A classic sunglasses lens: a rounded box, a touch wider at the brow, the outer corner raised."""
    pts = []
    for k in range(n):
        a = 2 * math.pi * k / n
        c, sn = math.cos(a), math.sin(a)
        x = math.copysign(abs(c) ** 0.45, c) * w / 2
        z = math.copysign(abs(sn) ** 0.55, sn) * h / 2
        if z < 0:
            x *= 0.88
        z += 0.12 * x * (1 if cx > 0 else -1) * (1 if z > 0 else 0.4)
        pts.append((cx + x, cy, EYE_Z + 0.1 + z))
    return pts


def item_shades():
    frame = material("shadesframe", "#141416", rough=0.25, noise=0.08, noise_scale=20)
    lens = material("shadeslens", "#2a3140", rough=0.05, stripes="Z", noise=0.6, noise_scale=1.4)
    hinge = material("shadeshinge", "#b9c2cc", metal=1.0)
    objs = arms(frame, r=0.16, z=EYE_Z + 0.75)
    for side in (-1, 1):
        cx = side * 2.0
        objs.append(curve_tube(shade_lens(cx, -4.66), 0.24, frame, cyclic=True, flat=0.7))
        objs.append(plate(shade_lens(cx, -4.56, 2.85, 1.85), 0.1, lens, axis="Y"))
        objs.append(obj(cube((0.35, 0.3, 0.3), (side * 3.62, -4.5, EYE_Z + 0.75)), hinge, bevel=0.06, segments=1))
    objs.append(curve_tube([(-0.55, -4.66, EYE_Z + 0.6), (0, -4.74, EYE_Z + 0.72), (0.55, -4.66, EYE_Z + 0.6)], 0.2, frame, flat=0.7))
    return {"bone": "head", "objects": objs, "view": "front"}


# ---- face masks: a curved shell in front of the face (clear of the skin's outer layer at y -4.5) ----

MASK_EYE_Z = 3.5


def mask_depth(x, z, bulge=0.55, nose=0.45):
    """How far the shell sits in front of the face at (x, z): rounded across, a soft nose bump."""
    return -4.62 - bulge * max(0.0, 1 - (x / 4.7) ** 2) - nose * math.exp(-((x / 0.85) ** 2 + ((z - 2.7) / 1.25) ** 2))


def almond(x, z, cx, cz=MASK_EYE_Z, w=1.25, h=0.62, tilt=0.18):
    """Inside an eye-hole shape (<1): an almond, its outer corner raised by ``tilt``."""
    dx = (x - cx) / w
    dz = (z - cz - tilt * (x - cx) * (1 if cx > 0 else -1)) / (h * (1 - 0.35 * dx * dx))
    return dx * dx + dz * dz


def seg_dist(x, z, a, b):
    ax, az = a
    bx, bz = b
    t = max(0.0, min(1.0, ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / ((bx - ax) ** 2 + (bz - az) ** 2)))
    return math.hypot(x - ax - t * (bx - ax), z - az - t * (bz - az))


def mask_shell(keep, mat, x0=-4.4, x1=4.4, z0=-0.3, z1=8.4, res=0.2, thick=0.3, depth=mask_depth):
    """The mask's shell: a grid over the face, cells where ``keep(x, z)`` is false cut away (outline, eye holes), its cut
    edges smoothed so they read as curves, then given ``thick``ness. Markings come from a {@link painted} ``mat``."""
    bm = bmesh.new()
    nx = int(round((x1 - x0) / res))
    nz = int(round((z1 - z0) / res))
    grid = {}
    faces = []
    for i in range(nx):
        for k in range(nz):
            cx = x0 + (i + 0.5) * res
            cz = z0 + (k + 0.5) * res
            if not keep(cx, cz):
                continue
            corners = []
            for di, dk in ((0, 0), (1, 0), (1, 1), (0, 1)):
                key = (i + di, k + dk)
                if key not in grid:
                    grid[key] = bm.verts.new((x0 + key[0] * res, 0, z0 + key[1] * res))
                corners.append(grid[key])
            faces.append(bm.faces.new(corners))
    bm.verts.ensure_lookup_table()
    # Smooth the stair-stepped cut edges along the outline.
    edge_verts = [v for v in bm.verts if v.is_boundary]
    for _ in range(10):
        moved = {}
        for v in edge_verts:
            ns = [e.other_vert(v) for e in v.link_edges if e.is_boundary]
            if len(ns) == 2:
                moved[v] = (v.co * 2 + ns[0].co + ns[1].co) / 4
        for v, co in moved.items():
            v.co = co
    for v in bm.verts:
        v.co.y = depth(v.co.x, v.co.z)
    mesh = bpy.data.meshes.new("mask")
    bm.to_mesh(mesh)
    bm.free()
    o = bpy.data.objects.new("mask", mesh)
    bpy.context.collection.objects.link(o)
    bpy.context.view_layer.objects.active = o
    o.data.materials.append(mat)
    sol = o.modifiers.new("solid", "SOLIDIFY")
    sol.thickness = thick
    sol.offset = 1.0
    sol.use_even_offset = True
    bev = o.modifiers.new("bevel", "BEVEL")
    bev.width = 0.06
    bev.segments = 2
    bev.limit_method = "ANGLE"
    for poly in o.data.polygons:
        poly.use_smooth = True
    return o


def face_outline(x, z, top=7.6, chin=0.25, width=4.25):
    """A full mask's outline: rounded forehead, narrowing to a soft chin."""
    if z < chin or z > top:
        return False
    t = (z - chin) / (top - chin)
    half = width * (0.62 + 0.38 * math.sin(min(1.0, t * 1.6) * math.pi / 2))
    if t > 0.75:
        half *= math.sqrt(max(0.0, 1 - ((t - 0.75) / 0.25) ** 2))
    return abs(x) <= half


def item_duality_mask():
    def mouth(x, z):
        # half a smile on the white side, half a frown on the black side
        if abs(x) > 1.75:
            return False
        centre = 1.75 + 0.2 * x * x if x > 0 else 1.95 - 0.16 * x * x
        return abs(z - centre) < 0.17 + 0.05 * (1 - abs(x) / 1.75)

    def keep(x, z):
        return face_outline(x, z) and almond(x, z, -2.0) > 1 and almond(x, z, 2.0) > 1 and not mouth(x, z)

    def pick(x, z):
        side = 1 if x > 0 else 0  # 1 = white
        rim = almond(x, z, 2.0 if x > 0 else -2.0, w=1.55, h=0.9) < 1
        # a teardrop under the black eye, a star by the white one
        tear = seg_dist(x, z, (-2.25, 2.55), (-2.35, 1.85)) < 0.16
        return 1 - side if rim or tear else side

    shell = mask_shell(keep, painted("dualface", ["#141417", "#f1efe9"], pick, tint_index=1))
    return {"bone": "head", "objects": [shell], "view": "front", "tex": 1024}


def item_kitsune_mask():
    white = material("kitsunewhite", "#f4f1ea", rough=0.3, noise=0.1, noise_scale=25)
    red = material("kitsuneinner", "#d0262f", rough=0.3)
    gold = material("kitsunegold", "#e3b04b", metal=1.0)

    def keep(x, z):
        return face_outline(x, z, top=7.4, chin=0.9) and almond(x, z, -1.9, w=1.15, h=0.42, tilt=0.38) > 1 \
            and almond(x, z, 1.9, w=1.15, h=0.42, tilt=0.38) > 1

    def pick(x, z):
        ax = abs(x)
        liner = almond(x, z, 1.9 if x > 0 else -1.9, w=1.5, h=0.7, tilt=0.38) < 1
        brow = seg_dist(ax, z, (1.0, 4.55), (2.9, 5.35)) < 0.2
        flame = abs(x) < 0.5 * max(0.0, 1 - abs(z - 6.2) / 0.9) and z > 5.3
        whisk = any(seg_dist(ax, z, (1.6, zz), (3.4, zz + d)) < 0.13 for zz, d in ((2.3, 0.25), (1.85, -0.05), (1.4, -0.35)))
        return 1 if liner or brow or flame or whisk else 0

    def depth(x, z):
        return mask_depth(x, z, bulge=0.6, nose=0.0)

    shell = mask_shell(keep, painted("kitsuneface", ["#f4f1ea", "#d0262f"], pick, tint_index=1), depth=depth)
    # the fox's snout (long and low, melting into the mask), a small black nose on its tip and two tall ears
    snout = sphere(1.3, (0, -5.35, 2.05), scale=(0.95, 1.15, 0.62), seg=32)
    obj(snout, white)
    nose = obj(sphere(0.26, (0, -6.78, 2.32), scale=(1.4, 0.7, 0.75), seg=12), material("kitsunenose", "#18181a", rough=0.3))
    objs = [shell, snout, nose]
    for side in (-1, 1):
        ear = plate([(side * 1.6, -4.9, 6.9), (side * 3.9, -4.9, 6.2), (side * 3.6, -4.9, 9.4)], 0.45, white, bevel=0.12, axis="Y")
        inner = plate([(side * 2.1, -5.0, 7.0), (side * 3.5, -5.0, 6.65), (side * 3.35, -5.0, 8.7)], 0.1, red, bevel=0.03, axis="Y")
        objs += [ear, inner]
    objs.append(obj(torus(0.35, 0.08, (0, -5.2, 7.25), rot=(90, 0, 0), major=16, minor=6), gold))
    return {"bone": "head", "objects": objs, "view": "front", "tex": 1024}


def item_oni_mask():
    gold = material("onigold", "#e2b04a", metal=1.0)
    bone = material("onifang", "#efe6cf", rough=0.4)

    def mouth(x, z):
        return abs(x) < 1.9 and 1.1 + 0.12 * x * x < z < 2.0 - 0.06 * x * x

    def keep(x, z):
        return face_outline(x, z, width=4.35) and almond(x, z, -2.0, w=1.05, h=0.55, tilt=-0.25) > 1 \
            and almond(x, z, 2.0, w=1.05, h=0.55, tilt=-0.25) > 1

    def pick(x, z):
        ax = abs(x)
        wrinkle = any(seg_dist(ax, z, a, b) < 0.13 for a, b in (((0.5, 5.6), (1.6, 6.3)), ((0.4, 6.4), (1.3, 6.9)), ((2.6, 2.0), (3.4, 2.9))))
        return 1 if mouth(x, z) or wrinkle else 0

    def depth(x, z):
        return mask_depth(x, z, bulge=0.65, nose=0.55)

    objs = [mask_shell(keep, painted("oniface", ["#b8202a", "#2a0d10"], pick, tint_index=0, rough=0.35), depth=depth)]
    for side in (-1, 1):
        # heavy gold brows, horns and fangs
        objs.append(curve_tube([(side * 0.8, depth(0.8, 4.4) - 0.3, 4.4), (side * 2.0, depth(2.0, 4.85) - 0.35, 4.85),
                                (side * 3.2, depth(3.2, 4.6) - 0.3, 4.6)], 0.26, gold))
        pts = [(side * (2.3 + 0.7 * t + 0.4 * t * t), -4.9 + 0.4 * t, 6.9 + 2.6 * t) for t in [k / 6 for k in range(7)]]
        objs.append(bent_tube(pts, [0.6 * (1 - 0.88 * k / 6) for k in range(7)], gold))
        objs.append(obj(cylinder(0.24, 0.9, (side * 1.3, depth(1.3, 1.35) - 0.25, 1.3), verts=12, r2=0.02), bone))
    return {"bone": "head", "objects": objs, "view": "front", "tex": 1024}


def item_neon_respirator():
    shell_m = material("respshell", "#16171b", rough=0.35, noise=0.1, noise_scale=30)
    glow = material("respglow", "#ff2bd6", rough=0.2, emission="#ff2bd6")
    metal = material("respmetal", "#8d949c", metal=1.0)

    def keep(x, z):
        top = 3.05 - 0.55 * abs(x) / 4.4 - 0.5 * max(0.0, 0.9 - abs(x)) * 0  # under the eyes
        return 0.2 < z < top + 0.45 * math.exp(-(x / 0.9) ** 2) and abs(x) < 4.35 - max(0.0, 0.6 - z) * 0.8

    def pick(x, z):
        line = abs(z - (2.55 - 0.42 * abs(x) / 4.4)) < 0.09 and abs(x) > 0.9
        slit = abs(x) < 0.85 and any(abs(z - zz) < 0.07 for zz in (0.9, 1.25, 1.6))
        return 1 if line or slit else 0

    def depth(x, z):
        return mask_depth(x, z, bulge=0.7, nose=0.7) - 0.25 * math.exp(-((x / 1.4) ** 2 + ((z - 1.3) / 0.9) ** 2))

    objs = [mask_shell(keep, painted("respface", ["#16171b", "#ff2bd6"], pick, tint_index=1), depth=depth)]
    for side in (-1, 1):
        # a filter on each cheek with a glowing ring
        c = (side * 2.75, depth(2.75, 1.25) - 0.35, 1.25)
        f = cylinder(0.85, 0.6, c, rot=(90, 0, 0), verts=28)
        objs.append(obj(f, shell_m, bevel=0.12, segments=2))
        objs.append(obj(torus(0.6, 0.09, (c[0], c[1] - 0.32, c[2]), rot=(90, 0, 0), major=24, minor=6), glow))
        objs.append(obj(cylinder(0.35, 0.2, (c[0], c[1] - 0.35, c[2]), rot=(90, 0, 0), verts=16), metal, bevel=0.05, segments=1))
        # straps round to the back of the head
        objs.append(curve_tube([(side * 4.2, -4.6, 2.1), (side * 4.62, -2.5, 2.4), (side * 4.62, 1.5, 2.9), (side * 3.5, 4.62, 3.1),
                                (0, 4.65, 3.15)], 0.16, shell_m, flat=0.4))
    return {"bone": "head", "objects": objs, "view": "front", "tex": 1024}


def item_monocle():
    gold = material("monogold", "#e0b04c", metal=1.0)
    glass = material("monoglass", "#d8eef5", rough=0.05, stripes="Z", noise=0.3, noise_scale=1.5)
    objs = [curve_tube(ring(-2.0, -4.64, EYE_Z, 1.45, 1.45, 40), 0.16, gold, cyclic=True),
            plate(ring(-2.0, -4.55, EYE_Z, 1.35, 1.35), 0.08, glass, axis="Y")]
    chain = [(-3.2 + 0.0 * k, -4.6 + 0.1 * k, EYE_Z - 1.0 - 0.8 * k) for k in range(5)]
    chain = [(-3.25, -4.62, EYE_Z - 0.8), (-3.9, -4.4, EYE_Z - 2.2), (-4.25, -3.5, EYE_Z - 3.2), (-4.3, -2.0, EYE_Z - 3.6)]
    objs.append(curve_tube(chain, 0.07, gold))
    return {"bone": "head", "objects": objs, "view": "front"}


# ---- wrist (watches) on the left arm ----

WRIST_Z = -7.6


def band_loop(mat, width=1.3, thick=0.32, z=WRIST_Z, gap=1.05):
    """A strap round the wrist (the arm is X -1..3, Y -2..2), open where the case sits on the outside (it ends under it)."""
    pts = rounded_square(2.25, 0.8, z, n=8, cy=0.0)
    pts = [(x + 1.0, y, zz) for x, y, zz in pts]
    # Start just past the case on one side and go round the back of the wrist to the other side of it.
    start = next(i for i, p in enumerate(pts) if p[0] > 3.0 and p[1] > gap)
    ordered = pts[start:] + pts[:start]
    path = [p for p in ordered if not (p[0] > 3.0 and abs(p[1]) < gap)]
    return obj(strap(path, width, thick, mat, cyclic=False), mat, bevel=0.08, segments=2)


def watch_face(case_mat, dial_mat, r=1.2, depth=0.55, bezel_mat=None, hands_mat=None, square=False):
    """The case on the outside of the wrist (+X), facing out."""
    x = 3.25 + depth / 2
    if square:
        case = obj(cube((depth, 2.6, 2.2), (x, 0, WRIST_Z)), case_mat, bevel=0.3, segments=3)
        dial = obj(cube((0.12, 2.0, 1.6), (x + depth / 2, 0, WRIST_Z)), dial_mat, bevel=0.05, segments=1)
        return [case, dial]
    case = obj(cylinder(r, depth, (x, 0, WRIST_Z), rot=(0, 90, 0), verts=40), case_mat, bevel=0.15, segments=3)
    dial = obj(cylinder(r * 0.8, 0.12, (x + depth / 2, 0, WRIST_Z), rot=(0, 90, 0), verts=40), dial_mat, bevel=0.03, segments=1)
    parts = [case, dial]
    if bezel_mat:
        parts.append(obj(torus(r * 0.92, 0.14, (x + depth / 2, 0, WRIST_Z), rot=(0, 90, 0), major=40, minor=8), bezel_mat))
    if hands_mat:
        hx = x + depth / 2 + 0.08
        parts.append(obj(cube((0.06, 0.12, r * 0.65), (hx, 0, WRIST_Z + r * 0.3)), hands_mat))
        parts.append(obj(cube((0.06, r * 0.5, 0.12), (hx, r * 0.22, WRIST_Z)), hands_mat))
    crown = obj(cylinder(0.18, 0.35, (x, -r - 0.1, WRIST_Z), rot=(90, 0, 0), verts=12), case_mat, bevel=0.04, segments=1)
    parts.append(crown)
    return parts


def item_diver_watch():
    steel = material("steel", "#c7cfd8", metal=1.0)
    dial = material("dial", "#0e2a4a", rough=0.3, noise=0.25, noise_scale=6)
    bezel = material("bezel", "#1f6fd1", rough=0.3, stripes="Y", noise=0.3, noise_scale=6)
    hands = material("hands", "#f4fbff", rough=0.4)
    return {"bone": "leftArm", "objects": [band_loop(steel)] + watch_face(steel, dial, bezel_mat=bezel, hands_mat=hands), "view": "side"}


def item_gold_watch():
    gold = material("goldw", "#e4b94f", metal=1.0)
    dial = material("champagne", "#f3e2b4", rough=0.3, noise=0.15, noise_scale=8)
    hands = material("goldhands", "#2b2b30", rough=0.4)
    return {"bone": "leftArm", "objects": [band_loop(gold, width=1.4)] + watch_face(gold, dial, r=1.1, bezel_mat=gold, hands_mat=hands), "view": "side"}


def item_smartwatch():
    black = material("rubberb", "#1b1c22", rough=0.7, noise=0.1, noise_scale=40)
    screen = material("screen", "#18d8ff", rough=0.1, stripes="Y", noise=0.6, noise_scale=4, emission="#18d8ff")
    return {"bone": "leftArm", "objects": [band_loop(black, width=1.5, thick=0.38)] + watch_face(black, screen, square=True), "view": "side"}


def item_digital_watch():
    resin = material("resin", "#3c3f45", rough=0.6, noise=0.1, noise_scale=30)
    lcd = material("lcd", "#a7c08a", rough=0.4, stripes="Y", noise=0.3, noise_scale=9)
    accent = material("accent", "#e23b3b", rough=0.4)
    parts = [band_loop(resin, width=1.6, thick=0.4)] + watch_face(resin, lcd, square=True)
    parts.append(obj(cube((0.1, 0.5, 0.25), (3.83, -1.0, WRIST_Z + 1.25)), accent, bevel=0.03, segments=1))
    return {"bone": "leftArm", "objects": parts, "view": "side"}


def item_leather_watch():
    leather = material("strap", "#6b3d1f", rough=0.7, noise=0.25, noise_scale=40)
    silver = material("silverw", "#d4d8de", metal=1.0)
    cream = material("cream_dial", "#f6efe0", rough=0.3, noise=0.08, noise_scale=6)
    hands = material("bluehands", "#1d3fa3", rough=0.4)
    return {"bone": "leftArm", "objects": [band_loop(leather, width=1.1)] + watch_face(silver, cream, r=1.25, depth=0.45, hands_mat=hands), "view": "side"}


# ---- feet (shoes) on the right leg, mirrored for the left ----

def foot_outline(z, toe=3.9, heel=2.45, outer=2.6, inner=2.05, n=28):
    """The sole's outline at height z: rounder at the toe (front -Y), the inner side straight (the legs touch)."""
    pts = []
    for k in range(n):
        a = 2 * math.pi * k / n
        c, s = math.cos(a), math.sin(a)
        x = (inner if c > 0 else outer) * (abs(c) ** 0.55) * (1 if c >= 0 else -1)
        y = (heel if s > 0 else toe) * (abs(s) ** 0.8) * (1 if s >= 0 else -1)
        pts.append((x, y - 0.35, z))
    return pts


def shoe(upper_m, sole_m, collar_z=-8.4, sole_t=0.9, toe_h=2.0, lace_m=None, tongue_m=None, toe_cap_m=None, extras=()):
    """A shoe round the bottom of the leg (Z -12..0): sole, a rounded toe box in front, the ankle piece round the leg."""
    objs = []
    sole = plate(foot_outline(-12.0 - sole_t + 0.25), sole_t, sole_m, bevel=min(0.35, sole_t / 2.4), axis="Z")
    objs.append(sole)
    top = -12.0 + 0.25
    bulb = sphere(1.0, (0, 0, 0), seg=32)
    for v in bulb.data.vertices:
        x, y, z = v.co
        z = max(z, -0.15)
        v.co = (-0.3 + x * 2.33, -1.55 + y * 2.65, top + (z + 0.15) * toe_h)
    objs.append(obj(bulb, upper_m))
    ankle_h = collar_z - top
    ankle = cube((4.6, 4.7, ankle_h), (-0.27, 0.15, top + ankle_h / 2))
    objs.append(obj(ankle, upper_m, bevel=0.95, segments=4, subsurf=2))
    if toe_cap_m:
        cap_o = sphere(1.0, (0, 0, 0), seg=28)
        for v in cap_o.data.vertices:
            x, y, z = v.co
            z = max(min(z, 0.25), -0.2)
            y = min(y, -0.15)
            v.co = (-0.3 + x * 2.38, -1.6 + y * 2.75, top + (z + 0.2) * toe_h * 0.85)
        objs.append(obj(cap_o, toe_cap_m))
    if tongue_m:
        objs.append(obj(cube((2.0, 0.4, ankle_h * 0.9), (-0.25, -2.35, top + ankle_h * 0.55)), tongue_m, bevel=0.18, segments=2))
    if lace_m:
        for k in range(4):
            yy = -2.9 + k * 0.6
            zz = top + toe_h * (0.86 + 0.07 * k) if k < 3 else top + ankle_h * 0.6
            objs.append(obj(cube((2.3, 0.28, 0.22), (-0.25, yy if k < 3 else -2.6, zz)), lace_m, bevel=0.08, segments=2))
    return objs + list(extras)


def item_sneakers():
    leather = material("leather", "#cf2a2d", rough=0.45, noise=0.2, noise_scale=45)
    white = material("rubber", "#f1efe9", rough=0.6, noise=0.06, noise_scale=25)
    lace = material("lace", "#fbfbfb", rough=0.8, stripes="X", noise=0.25, noise_scale=7)
    objs = shoe(leather, white, collar_z=-8.2, lace_m=lace, tongue_m=white, toe_cap_m=white)
    objs.append(obj(strap(rounded_square(2.38, 0.95, -8.25, cy=0.15), 0.6, 0.45, white, cyclic=True), white, bevel=0.12, segments=2))
    path = [(-2.6, 2.0, -11.0), (-2.65, 0.4, -10.3), (-2.62, -1.0, -9.9)]
    objs.append(obj(strap(path, 0.7, 0.18, white, normal_hint=(1, 0, 0)), white, bevel=0.05, segments=1))
    return {"bone": "rightLeg", "mirror": "leftLeg", "objects": objs}


def item_running_shoes():
    mesh_m = material("mesh", "#2bd9a0", rough=0.6, noise=0.3, noise_scale=80)
    sole = material("foam", "#f7f7f7", rough=0.7, stripes="Y", noise=0.15, noise_scale=12)
    accent = material("runaccent", "#1d2b4f", rough=0.5)
    objs = shoe(mesh_m, sole, collar_z=-9.9, sole_t=1.3, toe_h=1.7, lace_m=accent)
    objs.append(obj(strap([(-2.65, 1.9, -11.0), (-2.7, 0.0, -10.3), (-2.62, -2.2, -10.9)], 0.45, 0.15, accent, normal_hint=(1, 0, 0)), accent))
    return {"bone": "rightLeg", "mirror": "leftLeg", "objects": objs}


def item_combat_boots():
    leather = material("boot", "#22201f", rough=0.5, noise=0.3, noise_scale=35)
    sole = material("lug", "#14120f", rough=0.95, stripes="Y", noise=0.5, noise_scale=14)
    lace = material("bootlace", "#8a6b3f", rough=0.8)
    objs = shoe(leather, sole, collar_z=-6.0, sole_t=1.4, toe_h=2.2, lace_m=lace, tongue_m=leather)
    objs.append(obj(strap(rounded_square(2.4, 0.9, -6.1, cy=0.15), 0.8, 0.45, leather, cyclic=True), leather, bevel=0.12, segments=2))
    for k in range(3):
        objs.append(obj(cube((2.2, 0.25, 0.22), (-0.25, -2.6, -6.9 - k * 0.9)), lace, bevel=0.07, segments=2))
    return {"bone": "rightLeg", "mirror": "leftLeg", "objects": objs}


def item_sandals():
    cork = material("cork", "#c79a5e", rough=0.9, noise=0.45, noise_scale=90)
    bed = material("footbed", "#e3c69a", rough=0.8, noise=0.15, noise_scale=40)
    strap_m = material("sandalstrap", "#6e4321", rough=0.6, noise=0.2, noise_scale=40)
    buckle_m = material("buckle", "#d8c27a", metal=1.0)
    objs = [plate(foot_outline(-12.75, toe=3.7, heel=2.6), 0.75, cork, bevel=0.25, axis="Z"),
            plate(foot_outline(-12.05, toe=3.5, heel=2.45, outer=2.45, inner=1.95), 0.22, bed, bevel=0.08, axis="Z")]
    # Two wide straps over the foot (the leg's front half), anchored in the sole.
    for y, w in ((-2.45, 0.85), (-0.7, 1.0)):
        pts = [(-2.6, y, -11.9), (-2.62, y, -11.0), (-2.15, y, -10.25), (-0.3, y, -10.05), (1.6, y, -10.25), (2.08, y, -11.0), (2.08, y, -11.9)]
        objs.append(obj(strap(pts, w, 0.28, strap_m, normal_hint=(0, 1, 0)), strap_m, bevel=0.08, segments=2))
    # The heel strap round the back of the ankle, buckled on the outside.
    heel = [(-2.62, -0.2, -11.0), (-2.6, 1.4, -10.7), (-1.8, 2.45, -10.6), (0.0, 2.55, -10.6), (1.8, 2.45, -10.6), (2.08, 1.4, -10.7), (2.08, -0.2, -11.0)]
    objs.append(obj(strap(heel, 0.6, 0.26, strap_m, normal_hint=(0, 0, 1)), strap_m, bevel=0.06, segments=2))
    objs.append(obj(cube((0.2, 0.75, 0.7), (-2.78, -0.7, -10.75)), buckle_m, bevel=0.06, segments=1))
    objs.append(obj(cube((0.25, 0.6, 0.6), (-2.77, 1.0, -10.8)), buckle_m, bevel=0.06, segments=1))
    return {"bone": "rightLeg", "mirror": "leftLeg", "objects": objs}


def item_fluffy_slippers():
    fluff = material("plush", "#ffb3cf", rough=1.0, noise=0.3, noise_scale=90)
    white = material("plushwhite", "#fff7fb", rough=1.0, noise=0.2, noise_scale=90)
    dark = material("plusheye", "#2a2026", rough=0.3)
    body = sphere(2.9, (-0.2, -0.9, -10.7), scale=(0.9, 1.25, 0.62), seg=28)
    disp = body.modifiers.new("disp", "DISPLACE")
    tex = bpy.data.textures.new("plushn", "CLOUDS")
    tex.noise_scale = 0.4
    disp.texture = tex
    disp.strength = 0.25
    obj(body, fluff)
    sole = plate(foot_outline(-12.45, toe=3.7, heel=2.55), 0.55, material("plushsole", "#e88fb2", rough=1.0, noise=0.2, noise_scale=40), bevel=0.22, axis="Z")
    ears = []
    for s in (-1, 1):
        e = sphere(0.7, (-0.2 + s * 1.2, -3.0, -9.0), scale=(0.7, 0.35, 1.5), seg=14)
        ears.append(obj(e, fluff))
        ears.append(obj(sphere(0.25, (-0.2 + s * 0.9, -4.0, -9.9), seg=10), dark))
    nose = obj(sphere(0.3, (-0.2, -4.35, -10.4), seg=10), material("nose", "#ff6f9c", rough=0.5))
    return {"bone": "rightLeg", "mirror": "leftLeg", "objects": [body, sole, nose] + ears}


# ---- back ----

def item_backpack():
    canvas = material("canvas", "#4f8a4b", rough=0.85, noise=0.25, noise_scale=70)
    dark = material("canvasdark", "#335e30", rough=0.85, noise=0.25, noise_scale=70)
    buckle = material("buckles", "#d8c27a", metal=1.0)
    pack = obj(cube((7.2, 3.6, 8.8), (0, 3.9, -5.2)), canvas, bevel=1.2, segments=4, subsurf=1)
    lid = obj(cube((7.4, 3.9, 1.6), (0, 3.95, -1.2)), dark, bevel=0.7, segments=4, subsurf=1)
    pocket = obj(cube((5.0, 1.4, 3.6), (0, 6.0, -6.4)), dark, bevel=0.6, segments=4, subsurf=1)
    clips = [obj(cube((0.8, 0.3, 0.6), (x, 6.0, -1.9)), buckle, bevel=0.08, segments=1) for x in (-2.0, 2.0)]
    straps = []
    for x in (-2.5, 2.5):
        path = [(x, 2.2, -1.0), (x, 1.2, 0.35), (x, -0.6, 0.45), (x, -2.25, -0.2), (x, -2.35, -4.0), (x, -2.3, -7.6)]
        straps.append(obj(strap(path, 1.2, 0.4, dark, normal_hint=(1, 0, 0)), dark, bevel=0.1, segments=2))
    return {"bone": "body", "objects": [pack, lid, pocket] + clips + straps, "view": "back"}


def item_jetpack():
    metal = material("jetmetal", "#b9c2cc", metal=1.0)
    red = material("jetred", "#d33a2c", rough=0.4, noise=0.15, noise_scale=30)
    flame = material("flame", "#ffb13b", rough=0.2, stripes="Z", noise=0.6, noise_scale=3, emission="#ff8a1c")
    objs = []
    for x in (-2.0, 2.0):
        objs.append(obj(cylinder(1.55, 7.6, (x, 4.2, -4.6), verts=32), red, bevel=0.3, segments=3))
        objs.append(obj(sphere(1.55, (x, 4.2, -0.8), scale=(1, 1, 0.8), seg=24), red))
        objs.append(obj(cylinder(1.2, 1.4, (x, 4.2, -9.0), verts=28, r2=0.85), metal, bevel=0.15, segments=2))
        objs.append(obj(cylinder(0.75, 1.6, (x, 4.2, -10.4), verts=20, r2=0.05), flame))
    objs.append(obj(cube((5.0, 1.0, 6.0), (0, 2.6, -4.6)), metal, bevel=0.4, segments=3))
    for x in (-2.5, 2.5):
        path = [(x, 2.2, -1.5), (x, 1.0, 0.35), (x, -0.6, 0.45), (x, -2.25, -0.2), (x, -2.35, -4.0), (x, -2.3, -7.0)]
        objs.append(obj(strap(path, 1.0, 0.35, metal, normal_hint=(1, 0, 0)), metal, bevel=0.1, segments=2))
    return {"bone": "body", "objects": objs, "view": "back"}


def item_teddy_backpack():
    fur = material("teddy", "#a8743f", rough=1.0, noise=0.35, noise_scale=90)
    light = material("teddylight", "#e3c193", rough=1.0, noise=0.2, noise_scale=90)
    dark = material("teddydark", "#2b1d14", rough=0.4)
    body = sphere(3.1, (0, 4.6, -6.3), scale=(1.0, 0.75, 1.15), seg=28)
    head = sphere(2.4, (0, 4.6, -1.4), scale=(1.0, 0.8, 0.95), seg=28)
    for o in (body, head):
        d = o.modifiers.new("disp", "DISPLACE")
        tex = bpy.data.textures.new("tfur", "CLOUDS")
        tex.noise_scale = 0.3
        d.texture = tex
        d.strength = 0.2
        obj(o, fur)
    ears = [obj(sphere(0.9, (x, 4.5, 0.6), scale=(1, 0.6, 1), seg=16), fur) for x in (-1.9, 1.9)]
    muzzle = obj(sphere(1.0, (0, 6.3, -1.9), scale=(1.1, 0.6, 0.8), seg=16), light)
    eyes = [obj(sphere(0.3, (x, 6.4, -0.9), seg=10), dark) for x in (-0.9, 0.9)]
    nose = obj(sphere(0.35, (0, 6.95, -1.6), scale=(1.2, 0.8, 0.8), seg=10), dark)
    belly = obj(sphere(1.6, (0, 6.7, -6.6), scale=(1.0, 0.35, 1.1), seg=18), light)
    straps = []
    for x in (-2.4, 2.4):
        path = [(x, 2.2, -2.0), (x, 1.0, 0.35), (x, -0.6, 0.45), (x, -2.25, -0.2), (x, -2.35, -4.0), (x, -2.3, -7.4)]
        straps.append(obj(strap(path, 1.0, 0.35, dark, normal_hint=(1, 0, 0)), dark, bevel=0.1, segments=2))
    return {"bone": "body", "objects": [body, head, muzzle, nose, belly] + ears + eyes + straps, "view": "back"}


def item_quiver():
    leather = material("quiver", "#6a3f1f", rough=0.6, noise=0.3, noise_scale=40)
    trim = material("quivertrim", "#c9a24a", metal=1.0)
    wood = material("shaft", "#c8a06a", rough=0.6, stripes="Z", noise=0.2, noise_scale=10)
    feather = material("fletch", "#e8e2d6", rough=0.9, noise=0.2, noise_scale=40)
    red = material("fletchred", "#c0302a", rough=0.9)
    # Built upright round the origin (its mouth at z 0), then tilted and hung on the back.
    parts = [obj(cylinder(1.45, 8.5, (0, 0, -4.25), verts=28), leather, bevel=0.25, segments=3),
             obj(torus(1.5, 0.2, (0, 0, -0.1), major=32, minor=8), trim),
             obj(torus(1.5, 0.2, (0, 0, -8.3), major=32, minor=8), trim)]
    for k, (x, y) in enumerate(((-0.6, -0.3), (0.5, -0.4), (0.0, 0.5), (0.7, 0.4), (-0.6, 0.6))):
        parts.append(obj(cylinder(0.12, 3.0, (x, y, 1.2), verts=8), wood))
        parts.append(plate([(x, y - 0.05, 1.9), (x, y - 0.05, 3.2), (x + 0.5, y - 0.05, 2.6), (x + 0.5, y - 0.05, 1.7)], 0.1,
                           red if k % 2 else feather, bevel=0.02, axis="Y"))
    # Top over the left shoulder (+X, where the strap crosses), the bottom towards the right hip; on the back (y +2).
    place(parts, rot_y=22, offset=(1.0, 3.75, -2.6))
    strap_path = [(-3.6, 2.15, -9.5), (-1.0, 2.15, -4.8), (2.0, 2.15, -0.3), (3.0, 0.0, 0.45), (2.3, -2.25, -1.2), (-0.5, -2.3, -5.4), (-3.6, -2.2, -9.5)]
    parts.append(obj(strap(strap_path, 0.9, 0.3, leather, normal_hint=(0, 1, 0)), leather, bevel=0.08, segments=2))
    return {"bone": "body", "objects": parts, "view": "back"}


def item_katana_back():
    blade_m = material("steelblade", "#dfe6ee", metal=1.0)
    saya = material("saya", "#141418", rough=0.3, noise=0.15, noise_scale=30)
    wrap = material("tsuka", "#b3202e", rough=0.8, stripes="Z", noise=0.4, noise_scale=12)
    gold = material("tsuba", "#d4a843", metal=1.0)
    # Built along +Z from the guard (z 0): the sheath below, the handle above; then laid diagonally across the back.
    parts = [obj(cube((0.85, 0.5, 11.0), (0, 0, -5.5)), saya, bevel=0.22, segments=3, subsurf=1),
             obj(cube((0.95, 0.55, 0.6), (0, 0, -10.6)), gold, bevel=0.12, segments=2),
             obj(cylinder(0.9, 0.3, (0, 0, 0.15), rot=(0, 0, 0), verts=24), gold, bevel=0.08, segments=2),
             obj(cube((0.7, 0.48, 3.4), (0, 0, 2.0)), wrap, bevel=0.2, segments=3, subsurf=1),
             obj(cube((0.78, 0.52, 0.4), (0, 0, 3.85)), gold, bevel=0.1, segments=2)]
    for k in range(5):
        parts.append(obj(cube((0.74, 0.5, 0.14), (0, 0, 0.75 + k * 0.62)), saya, bevel=0.03, segments=1))
    # Handle over the left shoulder (+X, along the strap), the sheath's end at the right hip - all within the back.
    place(parts, rot_y=32, offset=(1.85, 2.6, -2.2))
    strap_path = [(-3.6, 2.15, -8.8), (0.0, 2.15, -3.9), (3.2, 2.15, -0.2), (3.6, 0.0, 0.45), (3.0, -2.25, -1.0), (-0.5, -2.3, -5.5), (-3.6, -2.2, -8.8)]
    parts.append(obj(strap(strap_path, 0.8, 0.3, saya, normal_hint=(0, 1, 0)), saya, bevel=0.08, segments=2))
    return {"bone": "body", "objects": parts, "view": "back"}


# ---- neck (necklaces): on the body, round the neck and down the chest; the game lets them swing a little ----

def neck_path(drop, n=56, width=3.2, back_z=-0.15):
    """The chain's line: behind the neck, over the shoulders, down to a point on the chest (front -Y)."""
    pts = []
    for k in range(n):
        a = 2 * math.pi * k / n
        # +Y half: round the back of the neck; -Y half: hanging down the chest
        x = width * math.sin(a)
        y = 2.25 * math.cos(a) if math.cos(a) > 0 else -2.32
        front = max(0.0, -math.cos(a))
        z = back_z - drop * front ** 1.6
        if math.cos(a) <= 0:
            x *= 1.0 - 0.35 * front ** 2
        pts.append((x, y if math.cos(a) > 0 else -2.32 - 0.05 * front, z))
    return pts


def chain(path, link_r, wire_r, mat, flat=1.0):
    """Interlocking links along a closed path: every other one turned a quarter."""
    objs = []
    pts = [Vector(p) for p in path]
    lengths = [(pts[(i + 1) % len(pts)] - pts[i]).length for i in range(len(pts))]
    total = sum(lengths)
    count = max(8, int(total / (link_r * 1.55)))
    # walk the path at equal steps
    def at(s):
        s %= total
        for i, l in enumerate(lengths):
            if s <= l:
                return pts[i].lerp(pts[(i + 1) % len(pts)], s / max(l, 1e-6)), (pts[(i + 1) % len(pts)] - pts[i]).normalized()
            s -= l
        return pts[0], (pts[1] - pts[0]).normalized()
    for k in range(count):
        p, d = at(total * k / count)
        o = torus(link_r, wire_r, (0, 0, 0), major=12, minor=6)
        o.scale = (1.0, flat, 1.0)
        bpy.ops.object.transform_apply(scale=True)
        # the ring's hole along the chain, every other link a quarter turn about it
        q = d.to_track_quat("X", "Z")
        o.rotation_mode = "QUATERNION"
        o.rotation_quaternion = q @ Matrix.Rotation(math.pi / 2 * (k % 2), 4, "X").to_quaternion() @ Matrix.Rotation(math.pi / 2, 4, "X").to_quaternion()
        o.location = p
        objs.append(obj(o, mat))
    return objs


def item_scarf():
    wool = wool_material("scarfwool", "#c62b35")
    stripe = wool_material("scarfstripe", "#f1ece2")
    objs = []
    # Wrapped twice round the neck, just below the head and outside the body (8 x 4): two soft, thick loops.
    for z, grow in ((-0.75, 0.0), (-1.95, 0.2)):
        loop = []
        for k in range(40):
            a = 2 * math.pi * k / 40
            c, s = math.cos(a), math.sin(a)
            loop.append(((3.95 + grow) * math.copysign(abs(c) ** 0.35, c), (2.1 + grow) * math.copysign(abs(s) ** 0.35, s), z))
        loop.append(loop[0])
        objs.append(fluffy(bent_tube(loop, [0.85] * len(loop), wool, cap=False), wool, strength=0.08, scale=0.12))
    # The knot on the chest and two ends hanging from it, white stripes near the bottom.
    objs.append(obj(sphere(1.05, (1.9, -3.0, -2.2), scale=(1.15, 0.7, 1.0), seg=20), wool))
    for dx, length in ((1.45, 6.0), (2.55, 5.0)):
        top, bottom = -2.6, -2.6 - length
        path = [(dx, -2.95, top + (bottom - top) * f) for f in (i / 8 for i in range(9))]
        objs.append(bent_tube(path, [(0.75, 0.32)] * len(path), wool))
        for z in (bottom + 0.75, bottom + 1.5):
            objs.append(bent_tube([(dx, -2.95, z + 0.2), (dx, -2.95, z - 0.2)], [(0.77, 0.34)] * 2, stripe))
    return {"bone": "body", "objects": objs, "view": "front"}


def item_fine_chain():
    gold = material("finegold", "#f0c64d", metal=1.0)
    return {"bone": "body", "objects": chain(neck_path(2.4), 0.2, 0.06, gold), "view": "front"}


def item_rope_chain():
    gold = material("ropegold", "#e8b93f", metal=1.0)
    objs = chain(neck_path(3.0), 0.28, 0.09, gold)
    # a small gold cross hanging at the bottom
    objs.append(obj(cube((0.32, 0.22, 1.6), (0, -2.25, -3.95)), gold, bevel=0.06, segments=2))
    objs.append(obj(cube((1.05, 0.22, 0.32), (0, -2.25, -3.65)), gold, bevel=0.06, segments=2))
    return {"bone": "body", "objects": objs, "view": "front"}


def item_cuban_chain():
    gold = material("cubangold", "#f4c542", metal=1.0)
    return {"bone": "body", "objects": chain(neck_path(2.0, width=3.35), 0.42, 0.17, gold, flat=0.55), "view": "front"}


def item_iced_chain():
    silver = material("icedsilver", "#e6ecf3", metal=1.0)
    ice = material("ice", "#ffffff", rough=0.05, noise=0.5, noise_scale=30)
    objs = chain(neck_path(2.2, width=3.35), 0.42, 0.17, silver, flat=0.55)
    # stones set along the front links
    for k, (x, y, z) in enumerate(neck_path(2.2, n=40, width=3.35)):
        if y < 0 and k % 2 == 0:
            objs.append(obj(sphere(0.16, (x, y - 0.2, z), seg=8), ice))
    return {"bone": "body", "objects": objs, "view": "front"}


def item_medallion_chain():
    gold = material("medalgold", "#f0c64d", metal=1.0)
    face = material("medalface", "#d9a531", rough=0.3, stripes="X", noise=0.4, noise_scale=6)
    objs = chain(neck_path(3.2), 0.24, 0.08, gold)
    objs.append(obj(cylinder(0.95, 0.25, (0, -2.25, -4.3), rot=(90, 0, 0), verts=32), gold, bevel=0.06, segments=2))
    objs.append(obj(cylinder(0.7, 0.1, (0, -2.4, -4.3), rot=(90, 0, 0), verts=32), face, bevel=0.03, segments=1))
    objs.append(obj(torus(0.22, 0.07, (0, -2.45, -3.25), rot=(0, 90, 0), major=12, minor=6), gold))
    return {"bone": "body", "objects": objs, "view": "front"}


# The material(s) a wearer's colour pick recolours, per item (its pixels are baked into <id>_mask.png).
TINT = {
    "top_hat": "band", "snapback": "brim", "beanie": "knit", "cowboy_hat": "leather", "crown": "ruby",
    "cat_ears": "pink", "bunny_ears": "bunnypink", "fox_ears": "foxfur", "devil_horns": "horn", "bear_ears": ("bear", "bearinner"),
    "aviators": "gold", "shades": "shadeslens",
    "duality_mask": "dualface", "kitsune_mask": ("kitsuneface", "kitsuneinner"), "oni_mask": "oniface",
    "neon_respirator": ("respface", "respglow"), "round_glasses": "wire", "cyber_visor": "glow", "heart_glasses": "heartframe", "monocle": "monogold",
    "diver_watch": "bezel", "gold_watch": "champagne", "smartwatch": "screen", "digital_watch": "resin", "leather_watch": "strap",
    "sneakers": "leather", "running_shoes": "mesh", "combat_boots": "bootlace", "sandals": ("cork", "footbed"), "fluffy_slippers": ("plush", "plushsole", "nose"),
    "backpack": ("canvas", "canvasdark"), "jetpack": "jetred", "teddy_backpack": "teddy", "quiver": ("quiver", "quivertrim", "shaft"), "katana_back": "tsuka",
    "witch_hat": "witchband", "santa_hat": "santared", "scarf": "scarfwool",
    "fine_chain": "finegold", "rope_chain": "ropegold", "cuban_chain": "cubangold", "iced_chain": "icedsilver", "medallion_chain": "medalgold",
}

# id -> (builder, slot, name, price, accent, description)
ITEMS = {
    "top_hat": (item_top_hat, "head", "Top Hat", 1400, "#c52f3c", "A tall black silk top hat with a red satin band - very distinguished."),
    "snapback": (item_snapback, "head", "Snapback", 1000, "#e8473f", "A black snapback with a flat red brim, a patch on the front and a strap at the back."),
    "beanie": (item_beanie, "head", "Knit Beanie", 900, "#e9a02b", "A cosy knitted beanie with a fold-up cuff and a fluffy pompom on top."),
    "cowboy_hat": (item_cowboy_hat, "head", "Cowboy Hat", 1200, "#7a4a26", "A leather cowboy hat with a curled brim and a pinched crown. Yeehaw."),
    "crown": (item_crown, "head", "Royal Crown", 2400, "#f0c442", "A golden crown with eight points and rubies and sapphires all the way round."),
    "cat_ears": (item_cat_ears, "ears", "Cat Ears", 900, "#ff9ccb", "Fluffy black cat ears with pink insides, on a thin headband."),
    "bunny_ears": (item_bunny_ears, "ears", "Bunny Ears", 900, "#ffb6d3", "Tall soft bunny ears in white and pink."),
    "fox_ears": (item_fox_ears, "ears", "Fox Ears", 1000, "#e8752a", "Orange fox ears with cream insides and dark tips."),
    "devil_horns": (item_devil_horns, "ears", "Devil Horns", 1100, "#c01b23", "Glossy red horns curling up from your head."),
    "bear_ears": (item_bear_ears, "ears", "Bear Ears", 900, "#7b5232", "Round brown teddy-bear ears. Cuddly."),
    "aviators": (item_aviators, "face", "Aviators", 1000, "#e2bd62", "Gold-framed aviator sunglasses with dark lenses - recolour the frame."),
    "duality_mask": (item_duality_mask, "mask", "Duality Mask", 1600, "#f1efe9", "Half black, half white - a smile on one side, a frown on the other."),
    "kitsune_mask": (item_kitsune_mask, "mask", "Kitsune Mask", 1800, "#d0262f", "A white fox spirit mask with red markings and tall ears."),
    "oni_mask": (item_oni_mask, "mask", "Oni Mask", 1800, "#b8202a", "A fierce red demon mask with golden horns, brows and fangs."),
    "neon_respirator": (item_neon_respirator, "mask", "Neon Respirator", 1500, "#ff2bd6", "A black cyber respirator with glowing neon lines and twin filters."),
    "shades": (item_shades, "face", "Classic Shades", 900, "#141416", "Bold black-framed sunglasses - recolour the lenses."),
    "round_glasses": (item_round_glasses, "face", "Round Glasses", 800, "#c99a3a", "Thin round golden wire frames - instant study mode."),
    "cyber_visor": (item_cyber_visor, "face", "Cyber Visor", 1500, "#26f0ff", "A wrap-around visor with a glowing cyan light strip."),
    "heart_glasses": (item_heart_glasses, "face", "Heart Glasses", 900, "#ff4f9a", "Pink heart-shaped sunglasses. Love is in the air."),
    "monocle": (item_monocle, "face", "Monocle", 1100, "#e0b04c", "A golden monocle on a fine chain. Splendid."),
    "diver_watch": (item_diver_watch, "wrist", "Diver Watch", 1300, "#1f6fd1", "A steel diver watch with a blue bezel and a deep blue dial."),
    "gold_watch": (item_gold_watch, "wrist", "Gold Watch", 1800, "#e4b94f", "A solid gold watch with a champagne dial."),
    "smartwatch": (item_smartwatch, "wrist", "Smartwatch", 1200, "#18d8ff", "A black smartwatch with a glowing screen."),
    "digital_watch": (item_digital_watch, "wrist", "Retro Digital", 900, "#a7c08a", "A chunky retro digital watch with a green LCD."),
    "leather_watch": (item_leather_watch, "wrist", "Classic Watch", 1100, "#6b3d1f", "A silver watch with a cream dial on a brown leather strap."),
    "sneakers": (item_sneakers, "feet", "High-Tops", 1200, "#cf2a2d", "Red high-top sneakers with white soles and laces."),
    "running_shoes": (item_running_shoes, "feet", "Runners", 1100, "#2bd9a0", "Light mint running shoes with a thick foam sole."),
    "combat_boots": (item_combat_boots, "feet", "Combat Boots", 1300, "#22201f", "Tall black leather boots with a chunky lug sole."),
    "sandals": (item_sandals, "feet", "Sandals", 700, "#c9a06a", "Summer sandals: a cork sole and two leather straps."),
    "fluffy_slippers": (item_fluffy_slippers, "feet", "Bunny Slippers", 1000, "#ffb3cf", "Fluffy pink bunny slippers, ears and all."),
    "backpack": (item_backpack, "back", "Adventure Pack", 1200, "#4f8a4b", "A trusty green adventure backpack with a front pocket and buckles."),
    "jetpack": (item_jetpack, "back", "Jetpack", 2000, "#d33a2c", "Twin red rocket tanks with flames at the nozzles."),
    "teddy_backpack": (item_teddy_backpack, "back", "Teddy Pack", 1300, "#a8743f", "A fluffy teddy bear riding on your back."),
    "quiver": (item_quiver, "back", "Quiver", 1100, "#6a3f1f", "A leather quiver full of arrows, slung across your back."),
    "katana_back": (item_katana_back, "back", "Katana", 1600, "#b3202e", "A katana in a black lacquered sheath, worn across your back."),
    "witch_hat": (item_witch_hat, "head", "Witch Hat", 1400, "#7b3fc4", "A tall, crooked witch hat with a purple band and a gold buckle."),
    "santa_hat": (item_santa_hat, "head", "Santa Hat", 1200, "#c8202c", "A floppy red Christmas hat with a fluffy white trim and a pompom."),
    "scarf": (item_scarf, "neck", "Knit Scarf", 1000, "#c62b35", "A thick, cosy knit scarf wrapped round your neck, the ends hanging down."),
    "fine_chain": (item_fine_chain, "neck", "Fine Gold Chain", 1000, "#f0c64d", "A thin, delicate gold chain - subtle, it swings a little as you move."),
    "rope_chain": (item_rope_chain, "neck", "Gold Cross Chain", 1200, "#e8b93f", "A gold chain with a small cross hanging at the bottom."),
    "cuban_chain": (item_cuban_chain, "neck", "Cuban Link", 1600, "#f4c542", "A thick, heavy gold Cuban link chain. Statement piece."),
    "iced_chain": (item_iced_chain, "neck", "Iced Cuban", 2200, "#e6ecf3", "A heavy silver Cuban link set with stones all along the front."),
    "medallion_chain": (item_medallion_chain, "neck", "Gold Medallion", 1400, "#f0c64d", "A gold chain with a big round medallion on your chest."),
}


# ---------------------------------------------------------------- bake and export


def finish(item_id, spec):
    objs = spec["objects"]
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    for o in objs:
        bpy.context.view_layer.objects.active = o
        for m in list(o.modifiers):
            bpy.ops.object.modifier_apply(modifier=m.name)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    item = bpy.context.active_object
    # The export reads the mesh's own coordinates: bake in the transform the first part carried (a turned ear, a link).
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    item.name = item_id
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.mesh.quads_convert_to_tris()
    bpy.ops.uv.smart_project(angle_limit=math.radians(60), island_margin=0.01)
    bpy.ops.object.mode_set(mode="OBJECT")
    # Bake the colour, then ambient occlusion, into one texture.
    color = bake(item, "DIFFUSE")
    ao = bake(item, "AO")
    pixels = [c * (0.5 + 0.5 * a) for c, a in zip(color, ao)]
    img = bpy.data.images.new(item_id + "_tex", TEX, TEX)
    img.pixels = [p if (i % 4) != 3 else 1.0 for i, p in enumerate(pixels)]
    img.scale(spec.get("tex", OUT_TEX), spec.get("tex", OUT_TEX))
    img.filepath_raw = str(OUT / f"{item_id}.png")
    img.file_format = "PNG"
    img.save()
    tint = TINT.get(item_id)
    if tint:
        # The mask: the tinted material white, everything else black.
        for slot in item.material_slots:
            nt = slot.material.node_tree
            bsdf = nt.nodes["Principled BSDF"]
            white = slot.material.name.split(".")[0] in ((tint,) if isinstance(tint, str) else tint)
            if white and "paint_mask" in slot.material:
                # painted: only its tinted colour's pixels
                nt.nodes["Paint"].image = bpy.data.images[slot.material["paint_mask"]]
                continue
            for link in list(bsdf.inputs["Base Color"].links):
                nt.links.remove(link)
            bsdf.inputs["Base Color"].default_value = (1, 1, 1, 1) if white else (0, 0, 0, 1)
        mask = bake(item, "DIFFUSE")
        mimg = bpy.data.images.new(item_id + "_mask", TEX, TEX)
        mimg.pixels = [p if (i % 4) != 3 else 1.0 for i, p in enumerate(mask)]
        mimg.scale(spec.get("tex", OUT_TEX), spec.get("tex", OUT_TEX))
        mimg.filepath_raw = str(OUT / f"{item_id}_mask.png")
        mimg.file_format = "PNG"
        mimg.save()
    export(item, OUT / f"{item_id}.vprop", mirror=False)
    if spec.get("mirror"):
        export(item, OUT / f"{item_id}_l.vprop", mirror=True)
    print("WEAR", item_id, "tris", len(item.data.polygons), flush=True)
    return item


def bake(item, kind):
    img = bpy.data.images.new("bake_" + kind, TEX, TEX, float_buffer=True)
    for slot in item.material_slots:
        nt = slot.material.node_tree
        node = nt.nodes.new("ShaderNodeTexImage")
        node.image = img
        nt.nodes.active = node
    scene = bpy.context.scene
    scene.cycles.samples = 32 if kind == "AO" else 1
    scene.render.bake.margin = 8
    if kind == "DIFFUSE":
        scene.render.bake.use_pass_direct = False
        scene.render.bake.use_pass_indirect = False
        scene.render.bake.use_pass_color = True
    bpy.ops.object.bake(type=kind)
    data = list(img.pixels)
    if kind == "AO":
        return [data[i - i % 4] for i in range(len(data))]
    return data


def export(item, path, mirror):
    """.vprop in bone space: x = X, y = -Z, z = Y (mirrored: x = -X, triangles flipped)."""
    mesh = item.data
    mesh.calc_loop_triangles()
    uv = mesh.uv_layers.active.data
    normals = mesh.corner_normals
    verts, index, keys = [], [], {}
    for tri in mesh.loop_triangles:
        corners = list(zip(tri.vertices, tri.loops))
        if mirror:
            corners = corners[::-1]
        for v, loop in corners:
            co = mesh.vertices[v].co
            n = normals[loop].vector
            u = uv[loop].uv
            sx = -1 if mirror else 1
            vert = (sx * co.x, -co.z, co.y, sx * n.x, -n.z, n.y, u.x, 1 - u.y)
            key = tuple(round(c, 5) for c in vert)
            if key not in keys:
                keys[key] = len(verts)
                verts.append(vert)
            index.append(keys[key])
    with open(path, "wb") as f:
        f.write(b"VPRP")
        f.write(struct.pack(">ii", 1, len(verts)))
        for vert in verts:
            f.write(struct.pack(">8f", *vert))
        f.write(struct.pack(">i", len(index)))
        f.write(struct.pack(f">{len(index)}i", *index))


# ---------------------------------------------------------------- preview


def preview(item_id, spec, item):
    """A Store picture: the item on a soft grey stand-in body, three-quarter view, transparent background."""
    scene = bpy.context.scene
    scene.render.resolution_x, scene.render.resolution_y = 384, 288
    scene.render.film_transparent = True
    scene.cycles.samples = 64
    textured = bpy.data.materials.new("textured")
    textured.use_nodes = True
    node = textured.node_tree.nodes.new("ShaderNodeTexImage")
    node.image = bpy.data.images.load(str(OUT / f"{item_id}.png"))
    textured.node_tree.links.new(node.outputs["Color"], textured.node_tree.nodes["Principled BSDF"].inputs["Base Color"])
    textured.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.45
    item.data.materials.clear()
    item.data.materials.append(textured)
    grey = material("standin", "#8e8e98", rough=0.7)
    bone = spec["bone"]
    stand = []
    if bone == "head":
        stand.append(obj(cube((8, 8, 8), (0, 0, 4)), grey, bevel=0.15))
    elif bone in ("rightLeg", "leftLeg"):
        stand.append(obj(cube((4, 4, 12), (0, 0, -6)), grey, bevel=0.1))
        if spec.get("mirror"):
            twin = item.copy()
            twin.data = item.data.copy()
            bpy.context.collection.objects.link(twin)
            twin.scale = (-1, 1, 1)
            twin.location = (3.8, 0, 0)
            stand.append(obj(cube((4, 4, 12), (3.8, 0, -6)), grey, bevel=0.1))
    elif bone == "body":
        stand.append(obj(cube((8, 4, 12), (0, 0, -6)), grey, bevel=0.1))
        stand.append(obj(cube((8, 8, 8), (0, 0, 4)), grey, bevel=0.15))
    else:
        stand.append(obj(cube((4, 4, 12), (1, 0, -4)), grey, bevel=0.1))
    # Camera fitted on the item.
    bpy.context.view_layer.update()
    pts = [item.matrix_world @ Vector(c) for c in item.bound_box]
    center = sum(pts, Vector()) / 8
    radius = max((p - center).length for p in pts)
    behind = spec.get("view") == "back"
    yaw = math.radians(150 if behind else -18 if spec.get("view") == "front" else 62 if spec.get("view") == "side" else -35)
    pitch = math.radians(spec.get("pitch", 18))
    dist = radius / math.tan(math.atan(18 / 70) * 288 / 384) * 1.05
    cam_data = bpy.data.cameras.new("cam")
    cam_data.lens = 70
    cam = bpy.data.objects.new("cam", cam_data)
    bpy.context.collection.objects.link(cam)
    cam.location = center + Vector((math.sin(yaw) * math.cos(pitch), -math.cos(yaw) * math.cos(pitch), math.sin(pitch))) * dist
    direction = center - cam.location
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    scene.camera = cam
    for loc, energy in (((8, -10, 14), 2500), ((-12, -4, 6), 900), ((4, 12, 8), 1200)):
        light = bpy.data.lights.new("light", "POINT")
        light.energy = energy * radius / 6
        lo = bpy.data.objects.new("light", light)
        lo.location = center + Vector(loc) * (radius / 5)
        bpy.context.collection.objects.link(lo)
    world = bpy.data.worlds.new("world")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.35, 0.36, 0.42, 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.6
    scene.world = world
    scene.render.filepath = str(OUT / f"{item_id}_preview.png")
    bpy.ops.render.render(write_still=True)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    OUT.mkdir(parents=True, exist_ok=True)
    for item_id in argv or ITEMS:
        reset()
        builder, slot, name, price, accent, description = ITEMS[item_id]
        spec = builder()
        item = finish(item_id, spec)
        preview(item_id, spec, item)
        import json
        (OUT / f"{item_id}.json").write_text(json.dumps({"slot": slot, "name": name, "price": price, "accent": accent,
                                                          "description": description, "bone": spec["bone"], "mirror": spec.get("mirror"),
                                                          "tint": item_id in TINT}, indent=1))


main()
