#!/usr/bin/env python3
"""Hand-made emote animations (the well-known dances Meshy's library doesn't have, and a few short gestures).

Each emote is a function of time that returns a Pose: the chest and pelvis turn around the hips, the body moves
(pixels), the head turns on the chest, arms in the chest's frame and legs in the pelvis' frame. compose() turns a
Pose into the six player model parts exactly like the retargeted Meshy clips (import_emotes.py), so both kinds are
the same .vemote frames.

Minecraft model angles: an arm's xRot < 0 lifts it forward (-pi/2 = pointing ahead), xRot > 0 swings it back;
zRot > 0 swings the RIGHT arm out to its side (and the left arm in), zRot < 0 the left arm out. Legs alike. The
chest's xRot > 0 leans forward. Model y is down, so a body offset of y = -1 is one pixel up.

Motion is built from eased curves (no linear tweens) with a little follow-through: the head lags the chest, arms
settle after a hit, the body dips on each step - that's what keeps them from looking robotic.
"""
import math

import numpy as np

HIP = np.array([0.0, 12.0, 0.0])
REST = {
    "head": np.array([0.0, 0.0, 0.0]), "body": np.array([0.0, 0.0, 0.0]),
    "rightArm": np.array([-5.0, 2.0, 0.0]), "leftArm": np.array([5.0, 2.0, 0.0]),
    "rightLeg": np.array([-1.9, 12.0, 0.0]), "leftLeg": np.array([1.9, 12.0, 0.0]),
}


class Pose:
    def __init__(self):
        self.chest = [0.0, 0.0, 0.0]
        self.pelvis = [0.0, 0.0, 0.0]
        self.move = [0.0, 0.0, 0.0]
        self.head = [0.0, 0.0, 0.0]
        self.rarm = [0.0, 0.0, 0.0]
        self.larm = [0.0, 0.0, 0.0]
        self.rleg = [0.0, 0.0, 0.0]
        self.lleg = [0.0, 0.0, 0.0]


def rot(e):
    x, y, z = e
    rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return rz @ ry @ rx


def compose(p, euler):
    """The six parts [x, y, z, xRot, yRot, zRot] of a Pose (euler: import_emotes.euler_continuous)."""
    chest, pelvis = rot(p.chest), rot(p.pelvis)
    move = np.array(p.move)

    def upper(q):
        return HIP + chest @ (q - HIP) + move

    def lower(q):
        return HIP + pelvis @ (q - HIP) + move

    return [
        (upper(REST["head"]), chest @ rot(p.head)),
        (upper(REST["body"]), chest),
        (upper(REST["rightArm"]), chest @ rot(p.rarm)),
        (upper(REST["leftArm"]), chest @ rot(p.larm)),
        (lower(REST["rightLeg"]), pelvis @ rot(p.rleg)),
        (lower(REST["leftLeg"]), pelvis @ rot(p.lleg)),
    ]


def render(fn, duration, fps, euler):
    frames = []
    for k in range(int(round(duration * fps)) + 1):
        parts = compose(fn(k / fps), euler)
        row = []
        for i, (pivot, r) in enumerate(parts):
            x, y, z = euler(r, frames[-1][i][3:] if frames else None)
            row.append([round(float(v), 2) for v in pivot] + [round(float(x), 3), round(float(y), 3), round(float(z), 3)])
        frames.append(row)
    return frames


# ---------------------------------------------------------------- curves

def smooth(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


def ease(t, a, b):
    """0 before a, 1 after b, smooth in between."""
    return smooth((t - a) / (b - a)) if b > a else float(t >= a)


def envelope(t, duration, fade_in=0.35, fade_out=0.45):
    """For one-shots: 0 -> 1 -> 0 so they start and end in the standing pose."""
    return min(ease(t, 0, fade_in), 1 - ease(t, duration - fade_out, duration))


def snap(s, k=0.55):
    """A sine with punchier holds at its ends (|s|^k keeps the sign): dance moves hit and hold, they don't float."""
    return math.copysign(abs(s) ** k, s)


def wave(t, period, phase=0.0):
    return math.sin(2 * math.pi * (t / period + phase))


def mix(a, b, w):
    return [x + (y - x) * w for x, y in zip(a, b)]


def breathe(p, t):
    p.chest[0] += 0.015 * wave(t, 3.2)
    p.head[0] -= 0.01 * wave(t, 3.2, 0.1)


# ---------------------------------------------------------------- the emotes

def no_no(beat=None):
    """Upright finger wag: right forearm up in front, the 'finger' wagging; head shaking, other hand on the hip."""
    d = 3.2

    def f(t):
        p = Pose()
        w = envelope(t, d, 0.4, 0.5)
        wag = ease(t, 0.45, 0.7) * (1 - ease(t, 2.5, 2.75))
        p.chest = [-0.05 * w, 0.12 * w, 0.0]  # a touch back and turned, a bit smug
        p.move = [0.0, 0.0, 0.3 * w]
        p.rarm = [-1.95 * w, -0.12 * w, (0.08 + 0.2 * wave(t, 0.34)) * wag * w - 0.25 * w]
        p.larm = [0.3 * w, 0.25 * w, -0.48 * w]  # hand on hip
        p.head = [0.06 * w + 0.03 * wave(t, 0.34, 0.25) * wag, 0.3 * wave(t, 0.68) * wag - 0.08 * w, 0.05 * w]
        p.rleg = [0.0, 0.0, 0.0]
        p.lleg = [-0.08 * w, 0.0, -0.06 * w]  # weight on the right foot
        breathe(p, t)
        return p
    return f, d


def facepalm(beat=None):
    d = 3.4

    def f(t):
        p = Pose()
        w = envelope(t, d, 0.2, 0.55)
        lift = ease(t, 0.05, 0.42)
        hit = math.exp(-max(0.0, t - 0.42) * 9) * (t > 0.42)  # the slap jolts the head back a little
        shake = ease(t, 0.9, 1.1) * (1 - ease(t, 2.3, 2.6))
        slump = ease(t, 0.42, 0.9) * w
        p.rarm = [(-2.3 * lift) * w, (-0.62 * lift) * w, (-0.08 * lift) * w]
        p.larm = [0.1 * slump, 0.0, -0.08 * slump]
        p.head = [(0.38 * lift - 0.12 * hit) * w, 0.14 * wave(t, 0.42) * shake, 0.0]
        p.chest = [0.14 * slump, 0.0, 0.03 * slump]
        p.move = [0.0, 0.4 * slump, 0.0]
        p.rleg = [-0.04 * slump, 0, 0]
        p.lleg = [0.03 * slump, 0, 0]
        breathe(p, t)
        return p
    return f, d


def floss(beat):
    """The Floss: straight arms swinging side to side, passing in front of and behind the body, hips the other way."""
    period = 2 * beat  # one side per beat
    bars = 8

    def f(t):
        p = Pose()
        s = snap(wave(t, period), 0.6)
        c = wave(t, period, 0.25)  # where the arms are crossing the body
        p.rarm = [0.5 * s, 0.0, 0.42 * s - 0.05]
        p.larm = [-0.5 * s, 0.0, 0.42 * s + 0.05]
        shift = -1.8 * s
        p.move = [shift, 0.35 * abs(c), 0.0]  # dips as the arms pass the body
        p.chest = [0.04, 0.1 * s, 0.07 * s]
        p.pelvis = [0.0, -0.12 * s, -0.05 * s]
        p.head = [0.0, -0.08 * s, -0.09 * s + 0.02 * c]
        for leg in (p.rleg, p.lleg):
            leg[2] = math.atan2(shift, 12) - 0.05 * s
        p.rleg[0] = -0.04 * max(0.0, s)
        p.lleg[0] = -0.04 * max(0.0, -s)
        return p
    return f, period * bars / 2


def griddy(beat):
    """The Griddy: alternating heel taps stepping forward, arms pumping opposite, hands up as 'goggles' every 4th bar."""
    loop = 16 * beat

    def f(t):
        p = Pose()
        b = t / beat
        step = b % 2  # one heel tap per beat, alternating feet
        side = 1 if int(b) % 2 == 0 else -1
        tap = math.sin(math.pi * min(1.0, step % 1 / 0.75)) if step % 1 < 0.75 else 0.0
        goggles = ease(b, 12, 12.5) * (1 - ease(b, 15.5, 16))
        bounce = abs(math.sin(math.pi * b))
        # heel tap: the leg kicks forward with the heel down, the body leans back over the standing leg
        kick = -0.55 * tap
        if side > 0:
            p.rleg = [kick, 0.0, 0.03]
            p.lleg = [0.18 * tap, 0.0, -0.03]
        else:
            p.lleg = [kick, 0.0, -0.03]
            p.rleg = [0.18 * tap, 0.0, 0.03]
        p.move = [0.4 * side * tap, 0.8 * bounce, -0.4 * tap]
        p.chest = [-0.08 * tap + 0.08, 0.08 * side * tap, 0.0]
        pump = 1.0 * tap * (1 - goggles)
        p.rarm = [-0.35 - pump * side, 0.0, 0.18]
        p.larm = [-0.35 + pump * side, 0.0, -0.18]
        # goggles: both hands at the eyes
        p.rarm = mix(p.rarm, [-2.3, -0.5, 0.0], goggles)
        p.larm = mix(p.larm, [-2.3, 0.5, 0.0], goggles)
        p.head = [0.05 - 0.1 * goggles, -0.1 * side * tap, 0.0]
        return p
    return f, loop


def l_dance(beat):
    """Take the L: an L on the forehead, little hops kicking one leg forward and out, the other arm swinging."""
    loop = 8 * beat

    def f(t):
        p = Pose()
        b = t / beat
        side = 1 if int(b) % 2 == 0 else -1
        ph = b % 1
        hop = math.sin(math.pi * ph)
        kick = smooth(min(1.0, ph / 0.45)) * (1 - smooth(max(0.0, (ph - 0.6) / 0.4)))
        p.move = [0.4 * side * kick, -1.4 * hop, 0.0]
        if side > 0:
            p.rleg = [-0.75 * kick, 0.0, 0.35 * kick]
            p.lleg = [0.08 * kick, 0.0, -0.05]
        else:
            p.lleg = [-0.75 * kick, 0.0, -0.35 * kick]
            p.rleg = [0.08 * kick, 0.0, 0.05]
        # the L: right arm up, hand flat on the forehead
        p.rarm = [-2.45, -0.58, 0.02 * hop]
        p.larm = [-0.4 * side * kick - 0.2, 0.0, -0.25 - 0.35 * kick]
        p.chest = [-0.04, -0.1 * side * kick, 0.05 * side * kick]
        p.head = [-0.08, 0.15 * side * kick, -0.06 * side]
        return p
    return f, loop


def afk(beat):
    """Falling asleep standing up: the head sinks, the body slumps and sways - then a jolt awake, and again."""
    loop = 8 * beat

    def f(t):
        p = Pose()
        x = (t % loop) / loop
        # sink for 3/4 of the loop, snap awake, settle
        sink = smooth(x / 0.74) if x < 0.78 else 1 - smooth((x - 0.78) / 0.05)
        jolt = math.exp(-max(0.0, x - 0.8) * 40) * (x > 0.8)
        sway = math.sin(2 * math.pi * t / (loop / 2))
        p.head = [0.62 * sink - 0.18 * jolt, 0.06 * sway, 0.1 * sink * sway]
        p.chest = [0.14 * sink - 0.05 * jolt, 0.0, 0.03 * sway]
        p.move = [0.3 * sway, 0.5 * sink, 0.0]
        p.rarm = [-0.12 - 0.12 * sink + 0.3 * jolt, 0.0, 0.06 + 0.04 * sway]
        p.larm = [-0.12 - 0.12 * sink + 0.3 * jolt, 0.0, -0.06 + 0.04 * sway]
        p.rleg = [-0.05 * sink, 0.0, 0.02 - 0.02 * sway]
        p.lleg = [0.04 * sink, 0.0, -0.02 - 0.02 * sway]
        return p
    return f, loop


AUTHORED = {"no_no": no_no, "facepalm": facepalm, "floss": floss, "griddy": griddy, "l_dance": l_dance, "afk": afk}
