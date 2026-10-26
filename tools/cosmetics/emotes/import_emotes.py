#!/usr/bin/env python3
"""Retargets Meshy animation clips (meshy_emotes.py animate -> work/anim_*.glb) onto the Minecraft player model.
A library for build_emotes.py, which builds the emotes.

The player model is six rigid boxes (head, body, two arms, two legs), so each part gets the direction its bones point,
not their bends. Per frame:
  - the pelvis (Hips joint) moves and turns the legs; the chest frame (hips -> neck, shoulder line) turns the
    upper body around the hips, carrying the head and arm pivots with it,
  - an arm points from the shoulder towards the hand (leaning towards the elbow, and pushed out of the torso - a
    Minecraft arm has no elbow), legs from hip to ankle, measured in the chest / pelvis frame; their twist comes
    from the bone's own rotation, so a raised or crossed arm can't flip,
  - the head takes the head joint's own rotation.
Euler angles are kept continuous from frame to frame, so the game's interpolation never spins through a flip.
Minecraft model space: y down, the player faces -Z, its right side is -X (the glTF clip: y up, faces +Z).
ModelPart rotations apply as Z * Y * X; pivots are in model pixels.

.vemote.json frames: [[part0..part5]], each part = [x, y, z, xRot, yRot, zRot] in the order head, body, rightArm,
leftArm, rightLeg, leftLeg.
"""
import json
import struct
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[3]
WORK = Path(__file__).resolve().parent / "work"
FPS = 20

M = np.diag([1.0, -1.0, -1.0])  # glTF -> Minecraft model space (180 degrees about X)
REST = {  # vanilla HumanoidModel pivots (model pixels)
    "head": np.array([0.0, 0.0, 0.0]), "body": np.array([0.0, 0.0, 0.0]),
    "rightArm": np.array([-5.0, 2.0, 0.0]), "leftArm": np.array([5.0, 2.0, 0.0]),
    "rightLeg": np.array([-1.9, 12.0, 0.0]), "leftLeg": np.array([1.9, 12.0, 0.0]),
}
HIP = np.array([0.0, 12.0, 0.0])


# ---------------------------------------------------------------- glTF

def read_glb(path):
    data = path.read_bytes()
    jl = struct.unpack_from("<I", data, 12)[0]
    gltf = json.loads(data[20:20 + jl])
    blob = data[20 + jl + 8:]

    def accessor(i):
        a = gltf["accessors"][i]
        v = gltf["bufferViews"][a["bufferView"]]
        width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
        start = v.get("byteOffset", 0) + a.get("byteOffset", 0)
        arr = np.frombuffer(blob, dtype="<f4", count=a["count"] * width, offset=start)
        return arr.reshape(a["count"], width) if width > 1 else arr
    return gltf, accessor


def quat_to_mat(q):
    x, y, z, w = q / np.linalg.norm(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def trs(t, r, s):
    m = np.eye(4)
    m[:3, :3] = quat_to_mat(np.array(r)) * np.array(s)
    m[:3, 3] = t
    return m


class Clip:
    def __init__(self, gltf, accessor, anim):
        self.gltf = gltf
        self.nodes = gltf["nodes"]
        self.name = {n.get("name"): i for i, n in enumerate(self.nodes)}
        self.parent = {}
        for i, n in enumerate(self.nodes):
            for c in n.get("children", []):
                self.parent[c] = i
        self.tracks = {}
        self.duration = 0.0
        for ch in anim["channels"]:
            s = anim["samplers"][ch["sampler"]]
            times = accessor(s["input"])
            values = accessor(s["output"])
            self.tracks[(ch["target"]["node"], ch["target"]["path"])] = (times, values)
            self.duration = max(self.duration, float(times[-1]))

    def sample(self, node, path, t, default):
        track = self.tracks.get((node, path))
        if track is None or t is None:
            return np.array(default, dtype=float)
        times, values = track
        if t <= times[0]:
            return values[0].astype(float)
        if t >= times[-1]:
            return values[-1].astype(float)
        k = int(np.searchsorted(times, t)) - 1
        f = (t - times[k]) / (times[k + 1] - times[k])
        a, b = values[k].astype(float), values[k + 1].astype(float)
        if path == "rotation":
            if np.dot(a, b) < 0:
                b = -b
            q = a * (1 - f) + b * f
            return q / np.linalg.norm(q)
        return a * (1 - f) + b * f

    def globals(self, t):
        """World matrix of every node at time t (None = rest pose)."""
        out = {}

        def world(i):
            if i in out:
                return out[i]
            n = self.nodes[i]
            local = trs(self.sample(i, "translation", t, n.get("translation", [0, 0, 0])),
                        self.sample(i, "rotation", t, n.get("rotation", [0, 0, 0, 1])),
                        self.sample(i, "scale", t, n.get("scale", [1, 1, 1])))
            m = world(self.parent[i]) @ local if i in self.parent else local
            out[i] = m
            return m
        for i in range(len(self.nodes)):
            world(i)
        return out

    def pos(self, g, name):
        return g[self.name[name]][:3, 3]

    def rot(self, g, name):
        m = g[self.name[name]][:3, :3]
        u, _, vt = np.linalg.svd(m)  # drop the armature's scale
        return u @ vt


# ---------------------------------------------------------------- retargeting

def frame(clip, g, name_up_from, name_up_to, left, right):
    up = clip.pos(g, name_up_to) - clip.pos(g, name_up_from)
    up /= np.linalg.norm(up)
    r = clip.pos(g, right) - clip.pos(g, left)
    r -= up * np.dot(r, up)
    r /= np.linalg.norm(r)
    fwd = np.cross(up, r)
    return np.column_stack([r, up, fwd])


def align(a, b):
    """Shortest rotation taking unit vector a to unit vector b."""
    a = a / np.linalg.norm(a)
    b = b / np.linalg.norm(b)
    v = np.cross(a, b)
    c = float(np.dot(a, b))
    if c < -0.9999:
        axis = np.cross(a, [1.0, 0, 0])
        if np.linalg.norm(axis) < 1e-3:
            axis = np.cross(a, [0, 0, 1.0])
        axis /= np.linalg.norm(axis)
        return 2 * np.outer(axis, axis) - np.eye(3)
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx / (1 + c)


def euler_zyx(r):
    """(xRot, yRot, zRot) with r = Rz(z) Ry(y) Rx(x) - how ModelPart applies its angles."""
    y = np.arcsin(np.clip(-r[2, 0], -1, 1))
    x = np.arctan2(r[2, 1], r[2, 2])
    z = np.arctan2(r[1, 0], r[0, 0])
    return x, y, z


def euler_continuous(r, previous):
    """euler_zyx, but of its two solutions (x, y, z) and (x + pi, pi - y, z + pi) the one nearest the previous frame,
    unwrapped onto it - so frames blend smoothly in game instead of spinning through a flip."""
    x, y, z = euler_zyx(r)
    if previous is None:
        return x, y, z
    best = None
    for cand in ((x, y, z), (x + np.pi, np.pi - y, z + np.pi)):
        c = [p + (v - p + np.pi) % (2 * np.pi) - np.pi for v, p in zip(cand, previous)]
        cost = sum((a - b) ** 2 for a, b in zip(c, previous))
        if best is None or cost < best[0]:
            best = (cost, c)
    return best[1]


def unclip_arm(y, side):
    """An arm direction (chest frame, model axes: y down, front -Z) turned forward until its 12 px box no longer
    passes through the torso or the head - the rig's elbow lets a hand cross the chest, a straight block can't."""
    shoulder = np.array([6.0 * side, 2.0, 0.0])  # the arm box's centre line (its pivot is 1 px further in)
    for _ in range(24):
        hit = False
        for k in np.linspace(0.3, 1.0, 8):
            p = shoulder + y * 12 * k
            # box half widths: torso 4 x 2, head 4 x 4, arm 2 - a pixel of overlap is fine
            torso = abs(p[0]) < 5 and -1 < p[1] < 12 and abs(p[2]) < 3
            head = abs(p[0]) < 5 and -9 < p[1] < 0 and abs(p[2]) < 5
            if torso or head:
                hit = True
                break
        if not hit:
            return y
        y = y + np.array([0.0, 0.0, -0.15])  # forward is -Z
        y /= np.linalg.norm(y)
    return y


def to_model(r):
    return M @ r @ M.T


def retarget(clip):
    rest = clip.globals(None)
    hips_rest = clip.pos(rest, "Hips")
    # model pixels per metre: the rig's hip height maps onto the player's 12 px legs
    feet_rest = (clip.pos(rest, "LeftFoot")[1] + clip.pos(rest, "RightFoot")[1]) / 2
    scale = 12.0 / max(hips_rest[1] - feet_rest, 1e-3)
    chest_rest = frame(clip, rest, "Hips", "neck", "LeftShoulder", "RightShoulder")
    pelvis_rest = frame(clip, rest, "Hips", "Spine01", "LeftUpLeg", "RightUpLeg")
    head_rest = clip.rot(rest, "Head")
    frames = []
    count = max(1, int(round(clip.duration * FPS)) + 1)
    for k in range(count):
        g = clip.globals(min(k / FPS, clip.duration))
        delta = M @ (clip.pos(g, "Hips") - hips_rest) * scale
        delta[0] = np.clip(delta[0], -6, 6)
        delta[2] = np.clip(delta[2], -6, 6)
        chest = to_model(frame(clip, g, "Hips", "neck", "LeftShoulder", "RightShoulder") @ chest_rest.T)
        pelvis = to_model(frame(clip, g, "Hips", "Spine01", "LeftUpLeg", "RightUpLeg") @ pelvis_rest.T)
        head = to_model(clip.rot(g, "Head") @ head_rest.T)

        def upper(p):
            return HIP + chest @ (p - HIP) + delta

        def lower(p):
            return HIP + pelvis @ (p - HIP) + delta

        def limb(basis, start, end, bone, mid=None):
            # direction from the joint to the hand / foot, twist from the bone itself: its front (+Z in the rig, the
            # way the character faces) carried along by the bone's rotation, so a crossed or raised arm can't flip
            d = basis.T @ (M @ (clip.pos(g, end) - clip.pos(g, start)))
            y = d / np.linalg.norm(d)
            if mid is not None:
                # a Minecraft arm has no elbow: lean towards the upper arm so a bent arm stays in front of the body
                u = basis.T @ (M @ (clip.pos(g, mid) - clip.pos(g, start)))
                y = 0.6 * y + 0.4 * u / np.linalg.norm(u)
                y = unclip_arm(y / np.linalg.norm(y), -1 if start.startswith("Right") else 1)
            front = basis.T @ (M @ (clip.rot(g, bone) @ clip.rot(rest, bone).T @ np.array([0.0, 0.0, 1.0])))
            back = -(front - y * np.dot(front, y))
            if np.linalg.norm(back) < 0.2:
                return basis @ align(np.array([0.0, 1.0, 0.0]), y)
            back /= np.linalg.norm(back)
            return basis @ np.column_stack([np.cross(y, back), y, back])

        parts = [
            (upper(REST["head"]), head),
            (upper(REST["body"]), chest),
            (upper(REST["rightArm"]), limb(chest, "RightArm", "RightHand", "RightArm", "RightForeArm")),
            (upper(REST["leftArm"]), limb(chest, "LeftArm", "LeftHand", "LeftArm", "LeftForeArm")),
            (lower(REST["rightLeg"]), limb(pelvis, "RightUpLeg", "RightFoot", "RightUpLeg")),
            (lower(REST["leftLeg"]), limb(pelvis, "LeftUpLeg", "LeftFoot", "LeftUpLeg")),
        ]
        row = []
        for i, (pivot, r) in enumerate(parts):
            x, y, z = euler_continuous(r, frames[-1][i][3:] if frames else None)
            row.append([round(float(v), 2) for v in pivot] + [round(float(x), 3), round(float(y), 3), round(float(z), 3)])
        frames.append(row)
    # start and end blend from/into the normal pose in the game, so trim nothing here
    return frames


# ---------------------------------------------------------------- preview

def preview(emote_id, frames):
    from PIL import Image, ImageDraw
    sizes = [(8, 8, 8), (8, 12, 4), (4, 12, 4), (4, 12, 4), (4, 12, 4), (4, 12, 4)]
    offsets = [(-4, -8, -4), (-4, 0, -2), (-3, -2, -2), (-1, -2, -2), (-2, 0, -2), (-2, 0, -2)]
    colours = [(222, 180, 140), (40, 180, 190), (200, 150, 120), (200, 150, 120), (70, 60, 170), (90, 80, 190)]
    picks = np.linspace(0, len(frames) - 1, 8).astype(int)
    sheet = Image.new("RGB", (8 * 160, 2 * 200), (30, 30, 38))
    for col, fi in enumerate(picks):
        for row, view in enumerate(("front", "side")):
            img = Image.new("RGB", (160, 200), (30, 30, 38))
            dr = ImageDraw.Draw(img)
            quads = []
            for (px, py, pz, xr, yr, zr), size, off, c in zip(frames[fi], sizes, offsets, colours):
                rx = np.array([[1, 0, 0], [0, np.cos(xr), -np.sin(xr)], [0, np.sin(xr), np.cos(xr)]])
                ry = np.array([[np.cos(yr), 0, np.sin(yr)], [0, 1, 0], [-np.sin(yr), 0, np.cos(yr)]])
                rz = np.array([[np.cos(zr), -np.sin(zr), 0], [np.sin(zr), np.cos(zr), 0], [0, 0, 1]])
                r = rz @ ry @ rx
                o = np.array(off, dtype=float)
                s = np.array(size, dtype=float)
                corners = [o + s * np.array([i, j, k]) for i in (0, 1) for j in (0, 1) for k in (0, 1)]
                pts = [r @ p + np.array([px, py, pz]) for p in corners]
                for face in ((0, 1, 3, 2), (4, 5, 7, 6), (0, 1, 5, 4), (2, 3, 7, 6), (0, 2, 6, 4), (1, 3, 7, 5)):
                    q = [pts[i] for i in face]
                    depth = np.mean([p[2] if view == "front" else -p[0] for p in q])
                    quads.append((depth, q, c))
            for depth, q, c in sorted(quads, key=lambda e: -e[0]):
                proj = [(80 + (p[0] if view == "front" else p[2]) * 4.5, 70 + p[1] * 4.5) for p in q]
                dr.polygon(proj, fill=c, outline=(15, 15, 20))
            sheet.paste(img, (col * 160, row * 200))
    sheet.save(WORK / f"preview_{emote_id}.png")
