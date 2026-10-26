#!/usr/bin/env python3
"""Builds every emote from emote_specs.py:

    python3 tools/cosmetics/emotes/build_emotes.py [id...] [--preview] [--bump]

For each emote: its animation (Meshy clips retargeted by import_emotes.py, or hand-made in author_emotes.py; several
sources are cross-faded into one), put on the beat of its music (music_sync.py) - the music's loop is cut from the
track and written as a sound - then its effect track, and out go

    src/client/resources/assets/velo-client/emotes/<id>.vemote.json      (+ emotes.json, the bundled list)
    src/client/resources/assets/velo-client/sounds/emote/music/<id>.ogg  (+ sounds.json entries; an .mp3 for the website)
    server/cosmetics-seed/emotes/<id>.vemote.json + the Store items in seed.json (rev bumped when the file changed)

--preview also renders work/pv/emote_<id>.png contact sheets. Needs the Meshy clips (meshy_emotes.py animate), the props
(meshy_props.py + import_props.py), the sounds (make_sounds.py) and the music: Kevin MacLeod tracks (CC BY 4.0) from
https://incompetech.com/music/royalty-free/mp3-royaltyfree/<title>.mp3 plus its pieces.json (tempo) in work/music/.
"""
import glob
import hashlib
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

import author_emotes  # noqa: E402
import emote_specs  # noqa: E402
import import_emotes as ie  # noqa: E402
import make_sounds  # noqa: E402
import music_sync as ms  # noqa: E402

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / "src/client/resources/assets/velo-client/emotes"
MUSIC_OUT = ROOT / "src/client/resources/assets/velo-client/sounds/emote/music"
WEB_MUSIC = ROOT / "velo-site/assets/emotes/music"  # the website's Store plays it next to the 3D try-on
SEED = ROOT / "server/cosmetics-seed"
FPS = 20
MUSIC_SR = 32000
MUSIC_LOOP_TARGET = 26.0  # seconds: the music loop is a whole number of dance loops about this long


# ---------------------------------------------------------------- animation sources

def clips():
    out = {}
    for f in sorted(glob.glob(str(HERE / "work/anim_*.glb"))):
        gltf, accessor = ie.read_glb(Path(f))
        for anim in gltf.get("animations", []):
            out[anim["name"]] = (gltf, accessor, anim)
    return out


def source_frames(src, clip_db, beat):
    kind = src[0]
    if kind == "meshy":
        _, name, start, end = src
        gltf, accessor, anim = clip_db[name]
        frames = ie.retarget(ie.Clip(gltf, accessor, anim))
        a = int(round(start * FPS))
        b = len(frames) if end is None else min(len(frames), int(round(end * FPS)) + 1)
        return frames[a:b]
    fn, duration = author_emotes.AUTHORED[src[1]](beat or 0.45)
    return author_emotes.render(fn, duration, FPS, ie.euler_continuous)


def crossfade(a, b, seconds=0.3):
    """b after a, blending the last frames of a into the first of b. Returns (frames, where b starts in seconds)."""
    m = min(int(seconds * FPS), len(a) // 2, len(b) // 2)
    A = np.array(a, dtype=float)
    B = np.array(b, dtype=float)
    mid = []
    for i in range(m):
        w = (i + 1) / (m + 1)
        w = w * w * (3 - 2 * w)
        d = B[i] - A[len(A) - m + i]
        d[:, 3:] = (d[:, 3:] + np.pi) % (2 * np.pi) - np.pi
        mid.append(A[len(A) - m + i] + d * w)
    out = list(A[: len(A) - m]) + mid + list(B[m:])
    return [np.round(f, 3).tolist() for f in out], (len(A) - m) / FPS


# ---------------------------------------------------------------- timing helpers

def ends(frames):
    """Hand / foot / head positions (model px) per frame: rh, lh, rf, lf, head."""
    out = []
    for fr in frames:
        pose = np.array(fr)

        def tr(part, local):
            px, py, pz, xr, yr, zr = pose[part]
            return author_emotes.rot((xr, yr, zr)) @ np.array(local) + np.array([px, py, pz])
        out.append([tr(2, [-1, 10, 0]), tr(3, [1, 10, 0]), tr(4, [0, 12, 0]), tr(5, [0, 12, 0]), tr(0, [0, -8, 0])])
    return np.array(out)


def foot_impacts(frames, min_gap=0.35):
    """When a foot lands hard: back on the ground (y >= 23.5 px) after being lifted (< 22.5) within the last 0.4 s."""
    e = ends(frames)
    times = []
    window = int(0.4 * FPS)
    for foot in (2, 3):
        y = e[:, foot, 1]
        for k in range(1, len(y)):
            if y[k] >= 23.5 and y[k - 1] < 23.5 and y[max(0, k - window):k].min() < 22.5:
                times.append(k / FPS)
    times.sort()
    out = []
    for t in times:
        if not out or t - out[-1] >= min_gap:
            out.append(t)
    return out


def accent_times(frames, n, min_gap):
    acc = ms.accents(frames, FPS)
    order = np.argsort(-acc)
    picked = []
    for k in order:
        t = k / FPS
        if all(abs(t - p) >= min_gap for p in picked):
            picked.append(t)
        if len(picked) == n:
            break
    return sorted(picked)


# ---------------------------------------------------------------- music

def music_entry(emote_id, spec_music, loop_seconds, beats, duration):
    """Cuts the emote's music and writes it; returns (event, credit)."""
    title = spec_music["title"]
    path = HERE / "work/music" / f"{title}.mp3"
    info = ms.catalogue(title)
    x = ms.decode(path)
    bpm, first, down = ms.beat_grid(x, float(info["bpm"]))
    beat = 60 / bpm
    full = ms.decode(path, MUSIC_SR)
    if spec_music.get("one_shot"):
        n = int(math.ceil(duration / beat))
        start = ms.pick_segment(x, bpm, first, down, n)
        seg = ms.cut(full, start, duration, loop=False, fade_out=spec_music.get("fade", 1.0), sr=MUSIC_SR)
        every = 0.0
    else:
        k = max(1, int(round(MUSIC_LOOP_TARGET / loop_seconds)))
        start = ms.pick_segment(x, bpm, first, down, beats * k)
        seg = ms.cut(full, start, loop_seconds * k, loop=True, sr=MUSIC_SR)
        every = loop_seconds * k
    seg = make_sounds.loudness(seg, target=0.17, peak=0.95)
    MUSIC_OUT.mkdir(parents=True, exist_ok=True)
    write_ogg(seg, MUSIC_OUT / f"{emote_id}.ogg")
    WEB_MUSIC.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(MUSIC_OUT / f"{emote_id}.ogg"), "-ac", "1", "-b:a", "80k",
                    str(WEB_MUSIC / f"{emote_id}.mp3")], check=True)
    make_sounds.update_sounds_json({f"emote.music.{emote_id}": {"sounds": [
        {"name": f"velo-client:emote/music/{emote_id}", "stream": True, "attenuation_distance": 32}]}})
    event = {"t": 0, "music": f"emote.music.{emote_id}", "volume": 1.0}
    if every:
        event["every"] = round(every, 4)
    return event, emote_specs.CREDIT.format(title=info["title"]), bpm


def write_ogg(x, path):
    old = make_sounds.SR
    make_sounds.SR = MUSIC_SR
    try:
        make_sounds.write_ogg(x, path, quality=3)
    finally:
        make_sounds.SR = old


def music_beat(spec):
    info = ms.catalogue(spec["music"]["title"])
    x = ms.decode(HERE / "work/music" / f"{spec['music']['title']}.mp3")
    bpm, _, _ = ms.beat_grid(x, float(info["bpm"]))
    return 60 / bpm


# ---------------------------------------------------------------- one emote

def build(spec, clip_db):
    emote_id = spec["id"]
    loop = spec.get("loop", False)
    beat = music_beat(spec) if spec.get("music") and not spec["music"].get("one_shot") else None
    # the animation
    starts = []
    frames = None
    for src in spec["src"]:
        part = source_frames(src, clip_db, beat)
        if frames is None:
            frames = part
            starts.append(0.0)
        else:
            frames, at = crossfade(frames, part)
            starts.append(at)
    raw_frames = frames
    beats = None
    remap = None
    authored = all(s[0] == "authored" for s in spec["src"])
    if loop and beat and not authored:
        frames, beats, remap = ms.sync_loop(frames, FPS, beat)
    elif loop:
        frames = ms.loop_seam(frames, FPS) if not authored else frames
        if beat:
            beats = int(round((len(frames) - 1) / FPS / beat))
    if spec.get("pad"):
        frames = frames + [frames[-1]] * int(spec["pad"] * FPS)
    n = len(frames)
    duration = (n - 1) / FPS
    if loop and beat:
        duration = beats * beat  # exact: the fps below makes the loop exactly this long, so it never drifts off the music
    fps = (n - 1) / duration
    # the effect track
    ctx = emote_specs.Ctx(duration, beat, beats)
    ctx.impacts = foot_impacts(frames)
    if len(spec["src"]) > 1:
        ctx.flair = remap(starts[1]) if remap else starts[1]
    if emote_id == "ymca":
        # the four letter poses, in the clip's own order, moved to where they ended up in the loop
        raw_letters = accent_times(raw_frames, 4, 0.7)
        ctx.letters = [remap(t) for t in raw_letters] if remap else raw_letters
    events = list(spec["events"](ctx))
    credit = None
    if spec.get("music"):
        event, credit, _ = music_entry(emote_id, spec["music"], duration, beats, duration)
        events.insert(0, event)
    events = sorted((e for e in events if e["t"] <= duration + 1e-6), key=lambda e: e["t"])
    data = {"id": emote_id, "name": spec["name"], "fps": round(fps, 5), "loop": loop, "frames": frames, "events": events}
    if credit:
        data["credit"] = credit
    return data


def main():
    wanted = [a for a in sys.argv[1:] if not a.startswith("--")]
    clip_db = clips()
    OUT.mkdir(parents=True, exist_ok=True)
    (SEED / "emotes").mkdir(parents=True, exist_ok=True)
    built = {}
    for spec in emote_specs.EMOTES:
        if wanted and spec["id"] not in wanted:
            continue
        data = build(spec, clip_db)
        text = json.dumps(data, separators=(",", ":"))
        (OUT / f"{spec['id']}.vemote.json").write_text(text)
        (SEED / "emotes" / f"{spec['id']}.vemote.json").write_text(text)
        built[spec["id"]] = hashlib.sha256(text.encode()).hexdigest()
        print(f"{spec['id']:15s} {len(data['frames']):4d} frames {data['fps']:7.3f} fps  {'loop' if data['loop'] else '    '} "
              f"{len(data['events']):3d} events  {len(text) // 1024:4d} KB")
        if "--preview" in sys.argv:
            ie.WORK = HERE / "work/pv"
            ie.preview("emote_" + spec["id"], data["frames"])
    # the bundled list: every emote, in order
    (OUT / "emotes.json").write_text(json.dumps({"emotes": [{"id": e["id"], "name": e["name"]} for e in emote_specs.EMOTES]},
                                                indent=1) + "\n")
    upsert_seed(built)
    # emotes that no longer exist
    keep = {e["id"] for e in emote_specs.EMOTES}
    for folder in (OUT, SEED / "emotes"):
        for f in folder.glob("*.vemote.json"):
            if f.name.split(".")[0] not in keep:
                f.unlink()
                print("removed", f.relative_to(ROOT))


def upsert_seed(built):
    """Store items: status released, prices / texts from the specs; rev goes up when the animation file changed."""
    path = SEED / "seed.json"
    seed = json.loads(path.read_text())
    for c in seed["collections"]:
        if c["id"] == "emotes":
            c["description"] = ("Dances with real music, trolls, celebrations - every emote with its own effects, sounds and props. "
                                "Hold B in game for your emote wheel; other Velo players see and hear them too.")
    items = {i["id"]: i for i in seed["items"]}
    hashes_path = HERE / "work/seed_hashes.json"
    hashes = json.loads(hashes_path.read_text()) if hashes_path.exists() else {}
    for spec in emote_specs.EMOTES:
        item_id = "emote_" + spec["id"]
        item = items.get(item_id)
        new = item is None
        if new:
            item = {"id": item_id, "type": "emote", "status": "released", "rev": 1}
            seed["items"].append(item)
            items[item_id] = item
        item.update({"name": spec["name"], "description": spec["desc"], "price": spec["price"],
                     "collection": spec.get("collection", emote_specs.EMOTES_COLLECTION), "accent": spec["accent"],
                     "files": {"anim": f"emotes/{spec['id']}.vemote.json"}})
        item.pop("tags", None)
        h = built.get(spec["id"])
        if h and hashes.get(spec["id"]) != h and not new:
            item["rev"] = item.get("rev", 1) + 1
        if h:
            hashes[spec["id"]] = h
    # drop emote items whose emote is gone
    keep = {"emote_" + e["id"] for e in emote_specs.EMOTES}
    seed["items"] = [i for i in seed["items"] if i["type"] != "emote" or i["id"] in keep]
    path.write_text(json.dumps(seed, indent="\t", ensure_ascii=False) + "\n")
    hashes_path.write_text(json.dumps(hashes, indent=1))


if __name__ == "__main__":
    main()
