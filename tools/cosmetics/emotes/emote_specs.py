"""What every emote is: where its animation comes from, its music, its effect track and its Store page.

build_emotes.py turns these into .vemote.json files. Effects are Velo's own (EmoteFx, no vanilla particles):

    fx(t, type, at, shape, color, ...)   type: burst fountain confetti ring flash text bolt (at once) |
                                               trail spiral rain orbit fog stream (for `dur` seconds)
    snd(t, name, volume, pitch)          a sound from make_sounds.py ("emote.<name>")
    prop(t, id, at, ...)                 a Meshy prop (import_props.py): at ground (body space x right, y up,
                                         z forwards) or rhand / lhand / head (x = the player's left, y = up the arm)
Anchors: head chest feet ring rhand lhand ground front above, or "@x,y,z" (body space).

Each events function gets a Ctx: the emote's length, and for music emotes the beat (seconds) and how many beats the
dance loop has - so effects land on the beat.
"""
import math

# Store collections the seasonal emotes join (they already exist on the server)
HALLOWEEN = "afterlife"
WINTER = "yuletide"
EMOTES_COLLECTION = "emotes"

CREDIT = "{title} - Kevin MacLeod (incompetech.com), CC BY 4.0"

PARTY = ["#ff4d6d", "#ffd24a", "#5cff9d", "#4dc3ff", "#b47cff", "#ff8a3c"]
FESTIVE = ["#ff3b3b", "#3bd16f", "#ffd24a", "#ffffff"]
NEON = ["#3ff0ff", "#ff4fd8", "#8a5cff"]


class Ctx:
    def __init__(self, duration, beat=None, beats=None):
        self.duration = duration
        self.beat = beat
        self.beats = beats

    def on_beats(self, every=1, offset=0):
        """Beat times in the loop: every `every` beats, starting at beat `offset`."""
        k = offset
        while self.beat and k < self.beats - 1e-6:
            yield k * self.beat
            k += every


def fx(t, kind, at="chest", shape="glow", color="#ffffff", count=8, speed=1.0, life=1.0, size=0.12, gravity=0.0, dur=0.0,
       radius=0.0, spin=0.0, text=None):
    e = {"t": round(t, 3), "fx": kind, "at": at, "shape": shape, "color": color, "count": count, "speed": speed, "life": life,
         "size": size}
    for k, v in (("gravity", gravity), ("dur", dur), ("radius", radius), ("spin", spin)):
        if v:
            e[k] = round(v, 3)
    if text:
        e["text"] = text
    return e


def snd(t, name, volume=1.0, pitch=1.0):
    return {"t": round(t, 3), "sound": "emote." + name, "volume": volume, "pitch": pitch}


def prop(t, prop_id, at="ground", dur=0.0, pos=(0, 0, 0), rot=(0, 0, 0), scale=0.5, anim="pop", vel=None, release=0.0, spin=0.0,
         pulse=0.0):
    e = {"t": round(t, 3), "prop": prop_id, "at": at, "dur": round(dur, 3), "pos": list(pos), "rot": list(rot), "scale": scale,
         "anim": anim}
    if vel:
        e["vel"] = list(vel)
    if release:
        e["release"] = release
    if spin:
        e["spin"] = spin
    if pulse:
        e["pulse"] = round(pulse, 4)
    return e


def at_xyz(x, y, z):
    return f"@{x:.2f},{y:.2f},{z:.2f}"


# ---------------------------------------------------------------- basics

def wave(c):
    out = [fx(0.5, "trail", "rhand", ["sparkle", "star"], ["#7cc8ff", "#ffffff"], count=34, speed=0.12, life=0.7, size=0.07, dur=2.6)]
    for i, t in enumerate((0.9, 1.8, 2.7)):
        out += [fx(t, "burst", "rhand", "star", ["#7cc8ff", "#d6f0ff"], count=9, speed=1.4, life=0.7, size=0.09),
                snd(t - 0.1, "chime", 0.6, 1.0 + 0.12 * i)]
    return out


def cheer(c):
    return [snd(0.5, "popper", 0.9), fx(0.55, "confetti", "rhand", "square", PARTY, count=26, speed=4.5, life=2.2, size=0.06, spin=6),
            fx(0.6, "confetti", "lhand", "square", PARTY, count=26, speed=4.5, life=2.2, size=0.06, spin=6),
            fx(0.6, "ring", "ground", "ring", "#ffcf5c", count=10, speed=0.6, life=0.8, size=0.08, radius=1.8),
            fx(0.85, "flash", "above", "sparkle", "#fff1b8", size=0.55, life=0.5), fx(0.85, "burst", "above", "star", "#ffcf5c", count=12,
                                                                                         speed=2.0, life=0.8, size=0.08)]


def shrug(c):
    return [snd(0.4, "boing", 0.7), fx(0.45, "text", "above", text="?", color="#d8dce8", size=0.22, life=1.4, speed=0.3),
            fx(0.5, "burst", "head", "smoke", ["#c9ccd6", "#a9adb8"], count=6, speed=0.5, life=0.9, size=0.15)]


def bow(c):
    return [snd(1.4, "magic", 0.8), fx(1.5, "spiral", "ground", ["sparkle", "star"], ["#b98cff", "#e8dcff"], count=60, speed=1.1, life=1.2,
                                       size=0.07, dur=2.0, radius=0.75),
            fx(3.25, "ring", "ground", "ring", "#b98cff", count=14, speed=0.4, life=1.0, size=0.07, radius=2.2),
            fx(3.25, "flash", "head", "glow", "#d8c4ff", size=0.6, life=0.6), snd(3.25, "chime", 0.8, 0.9),
            fx(3.4, "rain", "ground", ["star", "sparkle"], ["#b98cff", "#ffffff"], count=18, speed=0.5, life=1.6, size=0.06, dur=2.5,
               radius=1.2)]


def no_no(c):
    out = [snd(0.75, "tsk", 0.9), snd(1.85, "tsk", 0.9, 1.05),
           fx(0.8, "text", "above", text="X", color="#ff5a5a", size=0.26, life=1.8, speed=0.15)]
    for t in (0.85, 1.55, 2.25):
        out.append(fx(t, "burst", "rhand", "star", ["#ff7a7a", "#ffffff"], count=5, speed=0.9, life=0.45, size=0.06))
    return out


def flex(c):
    return [snd(0.05, "power_up", 0.7), snd(0.6, "zap", 0.9),
            fx(0.6, "burst", "rhand", "bolt", ["#ffe14d", "#7cf4ff"], count=6, speed=1.6, life=0.5, size=0.12),
            fx(0.6, "burst", "lhand", "bolt", ["#ffe14d", "#7cf4ff"], count=6, speed=1.6, life=0.5, size=0.12),
            fx(0.6, "ring", "ground", "ring", "#ff9a3c", count=12, speed=0.8, life=0.7, size=0.07, radius=1.9),
            fx(0.6, "flash", "chest", "glow", "#ffb36b", size=0.8, life=0.4),
            fx(0.6, "trail", "rhand", "sparkle", "#ffcf7a", count=25, speed=0.2, life=0.5, size=0.06, dur=1.1),
            fx(0.6, "trail", "lhand", "sparkle", "#ffcf7a", count=25, speed=0.2, life=0.5, size=0.06, dur=1.1)]


def heart(c):
    big = at_xyz(0, 1.75, 0.45)
    return [snd(0.85, "heart", 0.9), fx(0.9, "flash", big, "heart", "#ff4f7b", size=0.32, life=1.4),
            fx(0.9, "burst", big, "heart", ["#ff4f7b", "#ff9ecf"], count=14, speed=1.6, life=1.4, size=0.09),
            fx(1.0, "rain", "ground", "heart", ["#ff4f7b", "#ff9ecf", "#ffd1e3"], count=6, speed=0.4, life=2.2, size=0.08, dur=3.4,
               radius=1.2),
            fx(1.0, "orbit", "chest", "heart", ["#ff4f7b", "#ff9ecf"], count=5, speed=1.6, size=0.08, dur=3.4, radius=0.8),
            fx(4.4, "fountain", "chest", "heart", ["#ff4f7b", "#ff9ecf"], count=10, speed=2.2, life=1.4, size=0.08, gravity=1.5),
            snd(4.4, "heart", 0.6, 1.25)]


def facepalm(c):
    return [snd(0.42, "slap", 1.0), fx(0.45, "burst", "head", "star", "#ffffff", count=6, speed=1.2, life=0.4, size=0.06),
            fx(0.7, "burst", at_xyz(0.18, 1.95, 0.1), "drop", "#7cc8ff", count=2, speed=0.4, life=1.0, size=0.08, gravity=2.0),
            fx(1.5, "burst", "head", "smoke", ["#d0d3dc", "#b8bcc6"], count=4, speed=0.35, life=1.1, size=0.13)]


# ---------------------------------------------------------------- celebrations & power

def victory(c):
    trophy = at_xyz(0, 0.55, 0.95)
    return [prop(0.25, "trophy", "ground", dur=c.duration - 0.4, pos=(0, 0, 0.95), scale=0.55, anim="rise", spin=25),
            snd(0.3, "grind", 0.35, 1.4), snd(0.45, "fanfare", 0.9),
            fx(1.2, "flash", trophy, "sparkle", "#ffe38a", size=0.7, life=0.6),
            fx(1.2, "confetti", "front", "square", ["#ffd24a", "#fff3b0", "#ffb020"], count=40, speed=5.0, life=2.6, size=0.06, spin=6),
            snd(1.2, "popper", 0.8), fx(1.3, "orbit", trophy, "sparkle", ["#ffd24a", "#ffffff"], count=6, speed=2.2, size=0.08,
                                         dur=c.duration - 1.6, radius=0.45),
            fx(1.3, "spiral", "ground", "star", ["#ffd24a", "#fff3b0"], count=26, speed=1.0, life=1.4, size=0.06, dur=4.5, radius=0.6),
            snd(5.8, "crackle", 0.7), fx(5.8, "confetti", "front", "square", ["#ffd24a", "#fff3b0", "#ffb020"], count=36, speed=5.0,
                                          life=2.4, size=0.06, spin=6)]


def rage(c):
    out = [fx(0.2, "stream", "head", "smoke", ["#ff8a8a", "#d9d9d9"], count=10, speed=0.9, life=1.0, size=0.08, dur=c.duration - 0.6)]
    for i, t in enumerate(c.impacts):
        out += [snd(t, "stomp", 1.0, 0.9 + 0.05 * (i % 3)), fx(t, "ring", "feet", "ring", "#ff4d4d", count=8, speed=0.4, life=0.6,
                                                             size=0.06, radius=1.4),
                fx(t, "burst", "feet", "smoke", ["#8a7f78", "#b3aaa2"], count=7, speed=1.2, life=0.8, size=0.16),
                fx(t + 0.05, "flash", "above", "anger", "#ff4d4d", size=0.16, life=0.7)]
    return out


def thunder(c):
    hit = 1.65
    return [snd(0.3, "power_up", 0.8, 0.8),
            fx(0.3, "spiral", "ground", ["sparkle", "star"], ["#ffe14d", "#7cf4ff"], count=70, speed=1.4, life=1.0, size=0.07, dur=1.3,
               radius=0.65),
            fx(0.6, "trail", "rhand", "glow", ["#bfe6ff", "#ffffff"], count=40, speed=0.1, life=0.35, size=0.1, dur=1.05),
            fx(hit, "bolt", "ground", color="#9fd8ff", count=3, life=0.45, size=0.07, radius=1.6),
            snd(hit, "thunder", 1.0), snd(hit, "boom", 0.8),
            fx(hit, "ring", "ground", "ring", "#9fd8ff", count=18, speed=0.8, life=0.9, size=0.08, radius=3.6),
            fx(hit, "burst", "feet", "smoke", ["#6f7684", "#9aa1ad"], count=14, speed=2.2, life=1.2, size=0.22),
            fx(hit, "flash", "ground", "glow", "#cfefff", size=1.6, life=0.35),
            fx(hit + 0.15, "burst", "ground", "bolt", ["#ffe14d", "#9fd8ff"], count=8, speed=3.0, life=0.5, size=0.12)]


def backflip(c):
    return [snd(0.4, "whoosh", 0.9), fx(0.45, "trail", "feet", ["star", "sparkle"], ["#ffffff", "#7cc8ff"], count=50, speed=0.15,
                                            life=0.5, size=0.07, dur=0.6),
            snd(1.05, "stomp", 0.6, 1.2), fx(1.05, "ring", "ground", "ring", "#cfe8ff", count=10, speed=0.4, life=0.6, size=0.06,
                                             radius=1.5),
            fx(1.05, "burst", "feet", "smoke", ["#c9c2b8", "#a8a196"], count=8, speed=1.2, life=0.8, size=0.15),
            fx(1.2, "burst", "above", "star", ["#ffd24a", "#ffffff"], count=10, speed=1.6, life=0.7, size=0.08)]


def goal(c):
    land = at_xyz(0.1, 0.1, 3.4)
    return [prop(0, "soccer_ball", "ground", dur=c.duration, pos=(0.14, 0, 0.42), scale=0.26, anim="kick", vel=(0.0, 3.8, 6.5),
                 release=0.45, spin=420),
            snd(0.45, "kick_ball", 1.0), fx(0.45, "burst", at_xyz(0.14, 0.15, 0.45), "star", "#ffffff", count=8, speed=1.6, life=0.4,
                                           size=0.07),
            snd(1.3, "whistle", 0.8), snd(1.35, "stadium_horn", 0.6),
            fx(1.4, "text", "above", text="GOAL", color=["#ffffff", "#5cff7a", "#ffffff", "#5cff7a"], size=0.2, life=1.8, speed=0.2),
            fx(1.45, "confetti", "above", "square", ["#5cff7a", "#ffffff", "#ffd24a"], count=36, speed=3.5, life=2.0, size=0.06, spin=6),
            fx(1.25, "ring", land, "ring", "#5cff7a", count=8, speed=0.4, life=0.6, size=0.06, radius=1.0)]


# ---------------------------------------------------------------- trolls

def airhorn(c):
    out = [prop(0, "airhorn", "rhand", dur=c.duration, pos=(0, -0.1, 0.02), rot=(0, 90, 0), scale=0.42)]
    for i, t in enumerate((0.42, 0.72, 1.02)):
        out += [snd(t, "airhorn", 1.0, 1.0 if i < 2 else 0.97),
                fx(t, "ring", "rhand", "ring", "#ffffff", count=6, speed=0.5, life=0.4, size=0.05, radius=0.9),
                fx(t + 0.02, "burst", "head", "X", ["#ff3b3b", "#ffffff"], count=3, speed=1.6, life=0.5, size=0.11),
                snd(t + 0.05, "hitmarker", 0.6)]
    out.append(fx(1.05, "text", "above", text="MLG", color=["#ff3b3b", "#ffd24a", "#3ff0ff"], size=0.18, life=1.0, speed=0.3))
    return out


def sip_tea(c):
    return [prop(0, "teacup", "lhand", dur=c.duration - 0.3, pos=(0, -0.03, 0.06), rot=(57, 0, 0), scale=0.24),
            snd(0.35, "clink", 0.7), fx(0.4, "stream", "lhand", "smoke", ["#ffffff", "#e8eef5"], count=6, speed=0.35, life=1.1, size=0.05,
                                        dur=3.4),
            snd(4.1, "slurp", 0.9), fx(4.15, "burst", "head", "sparkle", "#ffffff", count=5, speed=0.6, life=0.6, size=0.06),
            fx(5.1, "burst", "head", "star", ["#fff1b8", "#ffffff"], count=8, speed=1.0, life=0.8, size=0.07), snd(5.1, "sparkle", 0.6)]


def afk(c):
    out = [fx(0.4, "stream", "head", "z", ["#ffffff", "#bfe0ff"], count=1, speed=0.32, life=2.6, size=0.12, dur=c.duration * 0.72)]
    out += [snd(c.duration * 0.55, "snore", 0.8), fx(c.duration * 0.55, "flash", at_xyz(0.12, 1.75, 0.25), "bubble", "#bfe0ff",
                                                       size=0.14, life=1.2),
            fx(c.duration * 0.8, "text", "above", text="!", color="#ffd24a", size=0.22, life=0.7, speed=0.4),
            snd(c.duration * 0.8, "boing", 0.5, 1.4)]
    return out


def l_dance(c):
    out = [fx(0, "rain", "ground", "L", ["#5cff7a", "#b8ffc6"], count=3, speed=0.5, life=2.4, size=0.1, dur=c.duration, radius=1.4)]
    for t in c.on_beats(4):
        out.append(fx(t, "text", "above", text="L", color="#5cff7a", size=0.3, life=c.beat * 3.5, speed=0.2))
    for t in c.on_beats(2, 1):
        out.append(fx(t, "burst", "feet", "star", ["#5cff7a", "#ffffff"], count=5, speed=1.0, life=0.4, size=0.06))
    return out


# ---------------------------------------------------------------- dances (music)

def silly_dance(c):
    out = [fx(0, "rain", "ground", ["note", "notes"], "rainbow", count=3, speed=0.4, life=2.4, size=0.1, dur=c.duration, radius=1.3)]
    for t in c.on_beats(2):
        out.append(fx(t, "burst", "head", ["note", "notes"], "rainbow", count=2, speed=0.9, life=1.1, size=0.1))
    for t in c.on_beats(8):
        out.append(fx(t, "ring", "ground", "ring", "rainbow", count=10, speed=0.4, life=0.7, size=0.06, radius=1.6))
    return out


def groove(c):
    out = [fx(0, "orbit", "above", "sparkle", ["#6cf0c2", "#ffffff", "#b47cff"], count=6, speed=2.0, size=0.08, dur=c.duration,
              radius=0.5)]
    for i, t in enumerate(c.on_beats(1)):
        out.append(fx(t, "ring", "ground", "ring", "#6cf0c2" if i % 2 == 0 else "#b47cff", count=0, life=c.beat * 1.2, radius=1.5))
    for t in c.on_beats(2, 1):
        out += [fx(t, "burst", "rhand", "sparkle", ["#6cf0c2", "#ffffff"], count=4, speed=1.0, life=0.4, size=0.06),
                fx(t, "burst", "lhand", "sparkle", ["#b47cff", "#ffffff"], count=4, speed=1.0, life=0.4, size=0.06)]
    return out


def shuffle(c):
    out = [fx(0, "trail", "feet", ["glow", "star"], NEON, count=40, speed=0.1, life=0.45, size=0.08, dur=c.duration),
           fx(0, "rain", "ground", "sparkle", NEON, count=4, speed=0.6, life=1.6, size=0.06, dur=c.duration, radius=1.6)]
    for i, t in enumerate(c.on_beats(1)):
        out.append(fx(t, "ring", "ground", "ring", NEON[i % 3], count=0, life=c.beat * 1.3, radius=1.3 + 0.4 * (i % 2)))
    for t in c.on_beats(8):
        out.append(snd(t, "laser", 0.3))
    return out


def ymca(c):
    out = [fx(0, "orbit", "above", "sparkle", PARTY, count=8, speed=1.6, size=0.07, dur=c.duration, radius=0.55)]
    for letter, t in zip("YMCA", c.letters):
        out += [fx(t, "text", "above", text=letter, color=PARTY[(len(out)) % len(PARTY)], size=0.3, life=c.beat * 1.8, speed=0.25),
                fx(t, "burst", "above", "star", PARTY, count=8, speed=1.6, life=0.6, size=0.07)]
    for t in c.on_beats(2):
        out.append(fx(t, "ring", "ground", "ring", PARTY[int(t / c.beat) % len(PARTY)], count=0, life=c.beat * 1.5, radius=1.5))
    return out


def hip_hop(c):
    box = (0.85, 0, 0.55)
    top = at_xyz(box[0], 0.45, box[2])
    out = [prop(0, "boombox", "ground", pos=box, rot=(-28, 0, 0), scale=0.62, anim="drop", pulse=1 / c.beat),
           snd(0.0, "bass_drop", 0.5)]
    for t in c.on_beats(2):
        out.append(fx(t, "burst", top, ["note", "notes"], ["#b47cff", "#ff4fd8", "#3ff0ff"], count=2, speed=1.0, life=1.3, size=0.1))
    for t in c.on_beats(1):
        out.append(fx(t, "ring", "ground", "ring", "#8a5cff", count=0, life=c.beat * 1.1, radius=1.2))
    return out


def breakdance(c):
    box = (-0.95, 0, 0.6)
    top = at_xyz(box[0], 0.45, box[2])
    out = [prop(0, "boombox", "ground", pos=box, rot=(28, 0, 0), scale=0.62, anim="drop", pulse=1 / c.beat)]
    for t in c.on_beats(2):
        out.append(fx(t, "burst", top, ["note", "notes"], ["#ffd24a", "#ff8a3c", "#4dc3ff"], count=2, speed=1.0, life=1.3, size=0.1))
    # the power move: dust, a swirl ring and trails off the feet
    f0 = c.flair
    out += [fx(f0, "trail", "feet", ["star", "glow"], ["#ffd24a", "#ffffff"], count=60, speed=0.2, life=0.5, size=0.08, dur=1.6),
            fx(f0, "fog", "ground", "smoke", ["#b8ada0", "#d6cdc2"], count=14, speed=0.5, life=1.4, size=0.2, dur=1.4, radius=0.6),
            fx(f0 + 0.2, "ring", "ground", "ring", "#ffd24a", count=12, speed=0.4, life=0.8, size=0.07, radius=1.8),
            snd(f0, "whoosh", 0.6), snd(f0 + 0.6, "whoosh", 0.5, 1.2)]
    return out


def floss(c):
    out = [fx(0, "trail", "rhand", "glow", "rainbow", count=40, speed=0.05, life=0.5, size=0.07, dur=c.duration),
           fx(0, "trail", "lhand", "glow", "rainbow", count=40, speed=0.05, life=0.5, size=0.07, dur=c.duration)]
    for t in c.on_beats(2):
        out.append(fx(t, "burst", "ring", "sparkle", "rainbow", count=4, speed=1.0, life=0.5, size=0.06))
    for t in c.on_beats(4):
        out.append(fx(t, "ring", "ground", "ring", "rainbow", count=0, life=c.beat * 1.6, radius=1.4))
    return out


def griddy(c):
    out = []
    for i, t in enumerate(c.on_beats(1)):
        out.append(fx(t + c.beat * 0.25, "burst", "feet", "star", ["#3ff0ff", "#ffffff"], count=4, speed=1.0, life=0.35, size=0.06))
        if i % 4 == 0:
            out.append(fx(t, "ring", "ground", "ring", "#3ff0ff", count=0, life=c.beat * 1.5, radius=1.4))
    g = 12 * c.beat
    out += [fx(g + c.beat * 0.4, "flash", "head", "glow", "#3ff0ff", size=0.6, life=0.4),
            fx(g + c.beat * 0.4, "burst", "head", "sparkle", ["#3ff0ff", "#ffffff"], count=10, speed=1.6, life=0.6, size=0.07)]
    return out


# ---------------------------------------------------------------- halloween

def zombie(c):
    return [prop(0, "tombstone", "ground", dur=c.duration - 0.2, pos=(0, 0, -0.85), scale=0.95, anim="rise"),
            snd(0.0, "grind", 0.8),
            fx(0.0, "fog", "ground", "smoke", ["#5f9a4e", "#3d6b35", "#7fb86b"], count=10, speed=0.25, life=2.2, size=0.3, dur=c.duration - 0.6,
               radius=1.3),
            fx(0.1, "burst", at_xyz(0, 0.1, -0.85), "smoke", ["#6b5a48", "#8a7a66"], count=10, speed=1.0, life=1.0, size=0.18),
            snd(0.75, "growl", 1.0),
            fx(0.95, "burst", "head", "skull", ["#9cff7a", "#d8ffcc"], count=4, speed=1.4, life=1.2, size=0.12, gravity=-0.6),
            fx(0.95, "ring", "ground", "ring", "#7fff5c", count=10, speed=0.3, life=0.9, size=0.06, radius=2.0),
            fx(1.0, "flash", "head", "glow", "#7fff5c", size=0.7, life=0.5)]


def pumpkin(c):
    reveal = 3.35
    face = at_xyz(0, 0.3, 1.05)
    return [fx(0.3, "trail", "rhand", "glow", "#b47cff", count=14, speed=0.1, life=0.6, size=0.06, dur=3.0),
            fx(0.3, "trail", "lhand", "glow", "#b47cff", count=14, speed=0.1, life=0.6, size=0.06, dur=3.0),
            prop(reveal, "pumpkin", "ground", dur=c.duration - reveal - 0.1, pos=(0, 0, 1.05), scale=0.6, anim="pop"),
            snd(reveal, "organ_sting", 0.9), snd(reveal + 0.1, "bats", 0.8),
            fx(reveal, "flash", face, "glow", "#ff8a1f", size=1.0, life=0.6),
            fx(reveal + 0.1, "burst", at_xyz(0, 0.55, 1.05), "bat", ["#2a1838", "#1a1022"], count=12, speed=2.6, life=1.8, size=0.15,
               gravity=-0.8),
            fx(reveal + 0.15, "stream", at_xyz(0, 0.6, 1.05), "flame", ["#ff8a1f", "#ffd24a", "#b47cff"], count=16, speed=0.9, life=0.7,
               size=0.11, dur=c.duration - reveal - 0.4),
            fx(reveal + 0.2, "fountain", face, "candy", ["#ff8a1f", "#b47cff", "#5cff7a"], count=10, speed=3.2, life=1.6, size=0.1,
               gravity=8, spin=5),
            fx(reveal, "ring", "front", "ring", "#ff8a1f", count=10, speed=0.4, life=0.8, size=0.06, radius=1.6)]


def ghost_party(c):
    out = [fx(0, "orbit", "chest", "ghost", ["#ffffff", "#e6dcff"], count=4, speed=1.3, size=0.2, dur=c.duration, radius=1.15),
           fx(0, "fog", "ground", "smoke", ["#4b3a6b", "#6a5296", "#2e2342"], count=6, speed=0.2, life=2.4, size=0.32, dur=c.duration,
              radius=1.4),
           snd(0.3, "ghost", 0.4)]
    for t in c.on_beats(2):
        out.append(fx(t, "burst", "head", ["note", "notes"], ["#b47cff", "#7fff5c"], count=2, speed=0.8, life=1.1, size=0.09))
    for t in c.on_beats(8, 4):
        out.append(fx(t, "burst", "above", "bat", ["#2a1838", "#1a1022"], count=6, speed=2.0, life=1.4, size=0.13, gravity=-0.5))
    return out


# ---------------------------------------------------------------- winter

def snowball(c):
    release = 3.25
    land = 4.15
    return [prop(0.2, "snowball", "rhand", dur=c.duration, pos=(0, -0.07, 0), scale=0.2, anim="throw", vel=(0.0, 2.6, 5.0),
                 release=release),
            fx(0.3, "trail", "rhand", ["snowflake", "glow"], ["#ffffff", "#bfe6ff"], count=10, speed=0.15, life=0.6, size=0.07,
               dur=release - 0.3),
            fx(0, "rain", "ground", "snowflake", ["#ffffff", "#dff4ff"], count=5, speed=0.4, life=2.8, size=0.07, dur=c.duration,
               radius=1.6),
            snd(release - 0.08, "whoosh", 0.8, 1.1),
            snd(land, "splat", 1.0), fx(land, "burst", at_xyz(0, 0.15, 4.6), ["snowflake", "glow"], ["#ffffff", "#dff4ff"], count=18,
                                         speed=2.2, life=0.8, size=0.08, gravity=4)]


def gift(c):
    pop = 1.05
    return [prop(0, "gift_box", "ground", dur=pop, pos=(0, 0, 0.95), scale=0.5, anim="drop"),
            snd(0.05, "sleigh_bells", 0.8), snd(0.45, "drumroll", 0.5),
            snd(pop, "popper", 1.0), snd(pop + 0.05, "chime", 0.8, 1.2),
            fx(pop, "confetti", at_xyz(0, 0.3, 0.95), "square", FESTIVE, count=46, speed=5.0, life=2.4, size=0.06, spin=6),
            fx(pop, "fountain", at_xyz(0, 0.4, 0.95), "candy", ["#ff3b3b", "#ffffff"], count=6, speed=3.5, life=1.4, size=0.1,
               gravity=8, spin=5),
            fx(pop, "flash", at_xyz(0, 0.5, 0.95), "star", "#fff3b0", size=0.9, life=0.5),
            fx(pop, "ring", "front", "ring", "#ffd24a", count=12, speed=0.5, life=0.8, size=0.06, radius=1.8),
            fx(pop + 0.2, "rain", "ground", ["star", "sparkle"], ["#ffd24a", "#ffffff"], count=10, speed=0.5, life=1.6, size=0.06,
               dur=1.6, radius=1.2)]


def jingle(c):
    out = [prop(0, "santa_hat", "head", pos=(0, -0.2, 0.02), rot=(0, 38, 0), scale=0.66, anim="hat"),
           fx(0, "rain", "ground", "snowflake", ["#ffffff", "#dff4ff"], count=7, speed=0.45, life=2.8, size=0.07, dur=c.duration, radius=1.7),
           fx(0, "orbit", "chest", ["sparkle", "glow"], FESTIVE, count=8, speed=1.4, size=0.07, dur=c.duration, radius=0.75)]
    for t in c.on_beats(8):
        out += [fx(t, "flash", "above", "star", "#ffd24a", size=0.4, life=c.beat * 2),
                fx(t, "burst", "above", "star", ["#ffd24a", "#ffffff"], count=6, speed=1.2, life=0.7, size=0.06)]
    for t in c.on_beats(4, 2):
        out.append(fx(t, "burst", "feet", "snowflake", ["#ffffff", "#dff4ff"], count=5, speed=1.0, life=0.6, size=0.06))
    return out


# ---------------------------------------------------------------- the list

def meshy(clip, start=0.0, end=None):
    return ("meshy", clip, start, end)


def authored(name):
    return ("authored", name)


def music(title, one_shot=False, fade=0.0):
    return {"title": title, "one_shot": one_shot, "fade": fade}


EMOTES = [
    # basics
    dict(id="wave", name="Wave", src=[meshy("Wave_One_Hand")], events=wave, price=600, accent="#7cc8ff",
         desc="A big friendly wave that leaves a trail of sparkles and chimes."),
    dict(id="cheer", name="Cheer", src=[meshy("Cheer_with_Both_Hands_Up")], events=cheer, price=900, accent="#ffcf5c",
         desc="Both arms up - confetti blasts out of your hands."),
    dict(id="shrug", name="Shrug", src=[meshy("Shrug")], events=shrug, price=500, accent="#b0b4c0",
         desc="Who knows? A shrug, a puff of confusion and a big question mark."),
    dict(id="bow", name="Bow", src=[meshy("Formal_Bow")], events=bow, price=900, accent="#b98cff",
         desc="A formal bow inside a spiral of magic that bursts as you bend."),
    dict(id="no_no", name="No No", src=[authored("no_no")], events=no_no, price=600, accent="#ff7a7a",
         desc="Wag your finger and shake your head - not today."),
    dict(id="flex", name="Flex", src=[meshy("Show_Both_Arm_Muscles")], events=flex, price=900, accent="#ff9a3c",
         desc="Show off both arms with a crackle of raw power."),
    dict(id="heart", name="Heart", src=[meshy("Big_Heart_Gesture")], events=heart, price=800, accent="#ff4f7b",
         desc="Make a big heart with your arms - and hearts rain down around you."),
    dict(id="facepalm", name="Facepalm", src=[authored("facepalm")], events=facepalm, price=500, accent="#9ab0c8",
         desc="When your teammate does THAT. Slap, sigh, sweat drop."),
    # celebrations & power
    dict(id="victory", name="Victory", src=[meshy("victory")], events=victory, price=1500, accent="#ffd24a",
         desc="A golden trophy rises in front of you - fanfare, confetti and a shower of stars."),
    dict(id="rage", name="Rage", src=[meshy("Angry_Stomp", 0, 1.75), meshy("Angry_Stomp", 0.45, 1.75), meshy("Angry_Stomp", 0.45, 2.8)],
         events=rage, price=800, accent="#ff4d4d",
         desc="Steam out of your ears and shockwaves under every stomp."),
    dict(id="thunder", name="Thunder Slam", src=[meshy("Charged_Ground_Slam")], events=thunder, price=1800, accent="#9fd8ff",
         desc="Charge up and slam the ground - lightning strikes all around you."),
    dict(id="backflip", name="Backflip", src=[meshy("Backflip")], events=backflip, price=1200, accent="#cfe8ff",
         desc="A clean backflip with a trail off your feet and a stuck landing."),
    dict(id="goal", name="Goal!", src=[meshy("Kick_a_Soccer_Ball")], pad=1.8, events=goal, price=1300, accent="#5cff7a",
         desc="Kick a ball into the distance - whistle, stadium horn, GOAL."),
    # trolls
    dict(id="airhorn", name="Air Horn", src=[meshy("Cheer_with_One_Hand_Up")], pad=0.4, events=airhorn, price=1100, accent="#ff3b3b",
         desc="BWAAAH BWAAAH BWAAAAAH. The loudest way to celebrate a play.", tags=["troll"]),
    dict(id="sip_tea", name="Sips Tea", src=[meshy("Stand_and_Drink")], events=sip_tea, price=1200, accent="#e8c48a",
         music=music("Fluffing a Duck", one_shot=True, fade=1.5),
         desc="Bring out the good china and sip. Their drama is none of your business.", tags=["troll"]),
    dict(id="afk", name="AFK", src=[authored("afk")], loop=True, events=afk, price=1000, accent="#bfe0ff",
         music=music("Local Forecast - Elevator"),
         desc="Nod off standing up to elevator music. Zzz... loops until you move.", tags=["troll"]),
    dict(id="l_dance", name="Take the L", src=[authored("l_dance")], loop=True, events=l_dance, price=1400, accent="#5cff7a",
         music=music("Spazzmatica Polka"),
         desc="An L on your forehead and a hop in your step - for every player you just beat.", tags=["troll", "dance"]),
    # dances
    dict(id="silly_dance", name="Silly Dance", src=[meshy("FunnyDancing_01")], loop=True, events=silly_dance, price=1400,
         accent="#ff6fd8", music=music("Monkeys Spinning Monkeys"),
         desc="A goofy dance in a rain of rainbow notes. Loops until you move.", tags=["dance"]),
    dict(id="groove", name="Groove", src=[meshy("Gangnam_Groove")], loop=True, events=groove, price=1500, accent="#6cf0c2",
         music=music("Show Your Moves"), desc="Ride the beat - the dance floor pulses under you. Loops until you move.", tags=["dance"]),
    dict(id="shuffle", name="Shuffle", src=[meshy("Arm_Circle_Shuffle")], loop=True, events=shuffle, price=1600, accent="#3ff0ff",
         music=music("Kick Shock"), desc="The festival shuffle with neon footwork on every beat.", tags=["dance"]),
    dict(id="ymca", name="Y.M.C.A.", src=[meshy("ymca_dance")], loop=True, events=ymca, price=1500, accent="#ffd24a",
         music=music("Disco con Tutti"), desc="Spell it out - every letter lights up above you on the beat.", tags=["dance"]),
    dict(id="hip_hop", name="Hip Hop", src=[meshy("Hip_Hop_Dance_2")], loop=True, events=hip_hop, price=1600, accent="#8a5cff",
         music=music("Basic Implosion"), desc="Drop a boombox and bounce - notes fly out on the beat.", tags=["dance"]),
    dict(id="breakdance", name="Breakdance", src=[meshy("Hip_Hop_Dance_4"), meshy("Thomas_Flair_to_Jump_Up")], loop=True,
         events=breakdance, price=2200, accent="#ff8a3c", music=music("Rocket Power"),
         desc="Toprock next to your boombox, then hit the floor for a flair. Loops until you move.", tags=["dance"]),
    dict(id="floss", name="Floss", src=[authored("floss")], loop=True, events=floss, price=1400, accent="#ff4fd8",
         music=music("Pixel Peeker Polka - faster"), desc="The one and only Floss, with rainbow trails off your hands.",
         tags=["dance"]),
    dict(id="griddy", name="Griddy", src=[authored("griddy")], loop=True, events=griddy, price=1500, accent="#3ff0ff",
         music=music("Zap Beat"), desc="Heel taps, arm pumps and the goggles. You know the one.", tags=["dance"]),
    # halloween
    dict(id="grave_rise", name="Grave Rise", src=[meshy("Zombie_Scream")], pad=0.8, events=zombie, price=1600, accent="#7fff5c",
         collection=HALLOWEEN, desc="A tombstone grinds out of the earth behind you - and you let out a zombie's scream.",
         tags=["halloween"]),
    dict(id="trick_or_treat", name="Trick or Treat", src=[meshy("Scheming_Hand_Rub"), meshy("Scheming_Hand_Rub")], events=pumpkin,
         price=1800, accent="#ff8a1f", collection=HALLOWEEN, music=music("Sneaky Snitch", one_shot=True, fade=1.0),
         desc="Rub your hands, scheme... and a grinning jack-o'-lantern bursts out, spitting bats and candy.", tags=["halloween"]),
    dict(id="ghost_party", name="Ghost Party", src=[meshy("Magic_Genie")], loop=True, events=ghost_party, price=1700,
         accent="#b47cff", collection=HALLOWEEN, music=music("One-eyed Maestro"),
         desc="Dance in the fog while friendly ghosts circle you. Loops until you move.", tags=["halloween", "dance"]),
    # winter
    dict(id="snowball", name="Snowball Fight", src=[meshy("Over_Shoulder_Throw", 0, 3.6)], pad=0.8, events=snowball, price=1300,
         accent="#bfe6ff", collection=WINTER, desc="Pack a snowball, wind up and let it fly. SPLAT.", tags=["winter"]),
    dict(id="gift", name="Surprise Gift", src=[meshy("happy_jump_m", 0, 2.4)], pad=0.6, events=gift, price=1300, accent="#ff3b3b",
         collection=WINTER, desc="A present drops at your feet and bursts into confetti and candy canes.", tags=["winter"]),
    dict(id="jingle", name="Jingle Jam", src=[meshy("Cherish_Pop_Dance")], loop=True, events=jingle, price=1700, accent="#3bd16f",
         collection=WINTER, music=music("Jingle Bells 3"),
         desc="Santa hat on, snow falling, Jingle Bells playing - dance it out. Loops until you move.", tags=["winter", "dance"]),
]

# wheel / Store order of the launch set
ORDER = [e["id"] for e in EMOTES]
