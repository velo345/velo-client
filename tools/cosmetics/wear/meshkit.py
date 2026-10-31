"""Small mesh toolkit for the bodywear models (numpy): smooth parametric shapes with UVs, and a z-buffer renderer for
the Store pictures and for checking the models.

A mesh is (P, N, UV, I): positions (n, 3), normals (n, 3), texture coordinates (n, 2) and triangles (m, 3). Every
shape maps its own (u, v) into a rectangle of the item's texture (``rect`` = u0, v0, u1, v1), so one texture serves
the whole item.
"""
import math

import numpy as np
from PIL import Image


# ---------------------------------------------------------------- building

def grid(points, rect, wrap_u=False):
    """A mesh from a (rows, cols, 3) grid of points: v runs down the rows, u along the columns."""
    rows, cols = points.shape[:2]
    u0, v0, u1, v1 = rect
    uu, vv = np.meshgrid(np.linspace(u0, u1, cols), np.linspace(v0, v1, rows))
    P = points.reshape(-1, 3)
    UV = np.stack([uu.ravel(), vv.ravel()], axis=1)
    I = []
    for r in range(rows - 1):
        for c in range(cols - 1):
            a, b, d, e = r * cols + c, r * cols + c + 1, (r + 1) * cols + c, (r + 1) * cols + c + 1
            I += [[a, d, b], [b, d, e]]
    I = np.array(I)
    return P, smooth_normals(P, I), UV, I


def smooth_normals(P, I, weld=True):
    """Area-weighted vertex normals; vertices at the same place (UV seams) share theirs."""
    fn = np.cross(P[I[:, 1]] - P[I[:, 0]], P[I[:, 2]] - P[I[:, 0]])
    N = np.zeros_like(P)
    for k in range(3):
        np.add.at(N, I[:, k], fn)
    if weld:
        keys = np.round(P, 4)
        _, inverse = np.unique(keys, axis=0, return_inverse=True)
        inverse = inverse.ravel()
        summed = np.zeros((inverse.max() + 1, 3))
        np.add.at(summed, inverse, N)
        N = summed[inverse]
    return N / np.maximum(1e-9, np.linalg.norm(N, axis=1))[:, None]


def merge(*meshes):
    P, N, UV, I = [], [], [], []
    base = 0
    for p, n, uv, i in meshes:
        P.append(p)
        N.append(n)
        UV.append(uv)
        I.append(i + base)
        base += len(p)
    return np.concatenate(P), np.concatenate(N), np.concatenate(UV), np.concatenate(I)


def transform(mesh, rotate=None, scale=(1, 1, 1), translate=(0, 0, 0), mirror_x=False):
    """Scales, rotates (3x3) and moves a mesh; mirroring flips x and the triangles' winding."""
    P, N, UV, I = mesh
    P = P * np.array(scale)
    N = N / np.array(scale)
    if rotate is not None:
        P = P @ rotate.T
        N = N @ rotate.T
    if mirror_x:
        P = P * np.array([-1, 1, 1])
        N = N * np.array([-1, 1, 1])
        I = I[:, ::-1]
    N = N / np.maximum(1e-9, np.linalg.norm(N, axis=1))[:, None]
    return P + np.array(translate), N, UV, I


def rot(axis, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    return {"x": np.array([[1, 0, 0], [0, c, -s], [0, s, c]]),
            "y": np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
            "z": np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[axis]


def lathe(profile, rect, seg=40):
    """A surface of revolution round the y axis: ``profile`` is (radius, y) from one end to the other (y up)."""
    th = np.linspace(0, 2 * math.pi, seg + 1)
    pts = np.array([[[r * math.cos(t), y, r * math.sin(t)] for t in th] for r, y in profile])
    return grid(pts, rect)


def superquad(rx, ry, rz, e_vert, e_horiz, rect, seg=36, rows=24, v_range=(0, 1)):
    """A superellipsoid: e 1 = round, small = boxy. ``v_range`` 0..1 from the top pole to the bottom (0..0.5 = a dome)."""
    def spow(x, e):
        return np.sign(x) * np.abs(x) ** e
    vs = np.linspace(v_range[0], v_range[1], rows)
    us = np.linspace(0, 1, seg + 1)
    pts = []
    for v in vs:
        phi = math.pi / 2 - v * math.pi
        row = []
        for u in us:
            th = u * 2 * math.pi
            cp, sp = spow(math.cos(phi), e_vert), spow(math.sin(phi), e_vert)
            row.append([rx * cp * spow(math.cos(th), e_horiz), ry * sp, rz * cp * spow(math.sin(th), e_horiz)])
        pts.append(row)
    return grid(np.array(pts), rect)


def tube(path, profile, rect, closed=False, up=(0, 1, 0)):
    """Sweeps a 2D ``profile`` (list of (x, y), closed loop) along a 3D ``path``: straps, frames, bands."""
    path = np.array(path, dtype=float)
    n = len(path)
    rings = []
    up = np.array(up, dtype=float)
    for i in range(n):
        if closed:
            t = path[(i + 1) % n] - path[i - 1]
        else:
            t = path[min(i + 1, n - 1)] - path[max(i - 1, 0)]
        t /= np.linalg.norm(t)
        side = np.cross(t, up)
        if np.linalg.norm(side) < 1e-6:
            side = np.cross(t, np.array([1, 0, 0]))
        side /= np.linalg.norm(side)
        normal = np.cross(side, t)
        rings.append([path[i] + side * px + normal * py for px, py in list(profile) + [profile[0]]])
    if closed:
        rings.append(rings[0])
    return grid(np.array(rings), rect)


def circle(r, n=16, sx=1.0, sy=1.0):
    return [(r * sx * math.cos(2 * math.pi * k / n), r * sy * math.sin(2 * math.pi * k / n)) for k in range(n)]


def rounded_rect(w, h, r, n=4):
    """A rounded rectangle outline (counter-clockwise), centred."""
    out = []
    for cx, cy, a0 in ((w / 2 - r, h / 2 - r, 0), (-w / 2 + r, h / 2 - r, 90), (-w / 2 + r, -h / 2 + r, 180), (w / 2 - r, -h / 2 + r, 270)):
        for k in range(n + 1):
            a = math.radians(a0 + 90 * k / n)
            out.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return out


def loft(sections, rect):
    """A surface through closed cross-sections (each a list of 3D points, same count): shoes, ears."""
    rings = [list(s) + [s[0]] for s in sections]
    return grid(np.array(rings, dtype=float), rect)


def cap(outline3d, center, rect):
    """A flat fan closing an outline (list of 3D points) round ``center``."""
    pts = np.array(list(outline3d) + [center], dtype=float)
    n = len(outline3d)
    I = np.array([[n, k, (k + 1) % n] for k in range(n)])
    u0, v0, u1, v1 = rect
    UV = np.array([[u0 + (u1 - u0) * (0.5 + 0.5 * math.cos(2 * math.pi * k / n)),
                    v0 + (v1 - v0) * (0.5 + 0.5 * math.sin(2 * math.pi * k / n))] for k in range(n)] + [[(u0 + u1) / 2, (v0 + v1) / 2]])
    fn = np.cross(pts[I[0, 1]] - pts[I[0, 0]], pts[I[0, 2]] - pts[I[0, 0]])
    fn = fn / max(1e-9, np.linalg.norm(fn))
    return pts, np.tile(fn, (len(pts), 1)), UV, I


def flip(mesh):
    P, N, UV, I = mesh
    return P, -N, UV, I[:, ::-1]


# ---------------------------------------------------------------- writing

def write_vprop(mesh, path):
    """.vprop: "VPRP", int 1, int vertexCount, per vertex x y z nx ny nz u v (big-endian floats), int indexCount, indices."""
    import struct
    P, N, UV, I = mesh
    with open(path, "wb") as f:
        f.write(b"VPRP")
        f.write(struct.pack(">ii", 1, len(P)))
        f.write(np.hstack([P, N, UV]).astype(">f4").tobytes())
        idx = I.reshape(-1)
        f.write(struct.pack(">i", len(idx)))
        f.write(idx.astype(">i4").tobytes())


# ---------------------------------------------------------------- rendering

def render(parts, texture, size=(384, 288), yaw=35, pitch=-14, ss=2, frame=None, background=(0, 0, 0, 0), extra=()):
    """Draws meshes (already in one space: skin pixels, y down, face to -z) with smooth light into an RGBA image.
    ``parts``: meshes using ``texture`` (a PIL image); ``extra``: (mesh, rgb) drawn in a flat colour (the stand-in
    body). ``frame``: (min xyz, max xyz) to fit in view, default everything."""
    W, H = size[0] * ss, size[1] * ss
    ya, pa = math.radians(yaw), math.radians(pitch)
    R = rot("x", -math.degrees(pa)) @ rot("y", -math.degrees(ya))
    tex = np.asarray(texture.convert("RGB"), dtype=np.float64) / 255.0
    th, tw = tex.shape[:2]
    jobs = [(m, None) for m in parts] + [(m, np.array(c, dtype=float) / 255.0) for m, c in extra]
    allp = np.concatenate([m[0] for m, _ in jobs])
    if frame is None:
        lo, hi = allp.min(axis=0), allp.max(axis=0)
    else:
        lo, hi = np.array(frame[0]), np.array(frame[1])
    corners = np.array([[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]) @ R.T
    cx, cy = (corners[:, 0].min() + corners[:, 0].max()) / 2, (corners[:, 1].min() + corners[:, 1].max()) / 2
    span = max(corners[:, 0].max() - corners[:, 0].min(), (corners[:, 1].max() - corners[:, 1].min()) * size[0] / size[1])
    scale = W * 0.8 / max(span, 1e-6)
    color = np.zeros((H, W, 3))
    alpha = np.zeros((H, W))
    depth = np.full((H, W), np.inf)
    light = np.array([-0.45, -0.75, -0.5])
    light /= np.linalg.norm(light)
    for (P, N, UV, I), flat in jobs:
        V = P @ R.T
        Nv = N @ R.T
        sx = (V[:, 0] - cx) * scale + W / 2
        sy = (V[:, 1] - cy) * scale + H / 2
        sz = V[:, 2]
        for tri in I:
            a, b, c = tri
            x0, y0, x1, y1, x2, y2 = sx[a], sy[a], sx[b], sy[b], sx[c], sy[c]
            area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if abs(area) < 1e-9:
                continue
            minx, maxx = max(int(min(x0, x1, x2)), 0), min(int(max(x0, x1, x2)) + 1, W - 1)
            miny, maxy = max(int(min(y0, y1, y2)), 0), min(int(max(y0, y1, y2)) + 1, H - 1)
            if minx > maxx or miny > maxy:
                continue
            xs, ys = np.meshgrid(np.arange(minx, maxx + 1) + 0.5, np.arange(miny, maxy + 1) + 0.5)
            w0 = ((x1 - xs) * (y2 - ys) - (x2 - xs) * (y1 - ys)) / area
            w1 = ((x2 - xs) * (y0 - ys) - (x0 - xs) * (y2 - ys)) / area
            w2 = 1 - w0 - w1
            inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not inside.any():
                continue
            z = w0 * sz[a] + w1 * sz[b] + w2 * sz[c]
            region = depth[miny:maxy + 1, minx:maxx + 1]
            hit = inside & (z < region)
            if not hit.any():
                continue
            n = w0[..., None] * Nv[a] + w1[..., None] * Nv[b] + w2[..., None] * Nv[c]
            n /= np.maximum(1e-9, np.linalg.norm(n, axis=-1))[..., None]
            # Seen from the camera (looking along +z): turn normals that face away (thin, two-sided parts).
            n = np.where((n[..., 2] > 0)[..., None], -n, n)
            diffuse = np.clip(-(n @ (R @ light)), 0, 1)
            half = (R @ light) + np.array([0, 0, 1.0])
            half /= np.linalg.norm(half)
            spec = np.clip(-(n @ half), 0, 1) ** 24
            if flat is None:
                u = w0 * UV[a, 0] + w1 * UV[b, 0] + w2 * UV[c, 0]
                v = w0 * UV[a, 1] + w1 * UV[b, 1] + w2 * UV[c, 1]
                base = tex[np.clip((v * th).astype(int), 0, th - 1), np.clip((u * tw).astype(int), 0, tw - 1)]
            else:
                base = np.broadcast_to(flat, xs.shape + (3,))
            shaded = base * (0.42 + 0.62 * diffuse[..., None]) + 0.25 * spec[..., None]
            region[hit] = z[hit]
            color[miny:maxy + 1, minx:maxx + 1][hit] = np.clip(shaded[hit], 0, 1)
            alpha[miny:maxy + 1, minx:maxx + 1][hit] = 1
    img = np.dstack([color, alpha]) * 255
    out = Image.fromarray(img.astype(np.uint8), "RGBA").resize(size, Image.LANCZOS)
    if background[3]:
        bg = Image.new("RGBA", size, background)
        bg.alpha_composite(out)
        return bg
    return out
