#!/usr/bin/env python3
"""Synthesises the emotes' sound effects (no vanilla sounds, no samples - every one is made here) and writes them as
mono Ogg Vorbis to assets/velo-client/sounds/emote/<name>.ogg, plus the sounds.json entries ("velo-client:emote.<name>").

    python3 tools/cosmetics/emotes/make_sounds.py [--play name]

Mono, so the game plays them in 3D at the emoting player. Music for the dances is made by make_music.py (it adds its
own sounds.json entries; this script keeps them).
"""
import json
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "src/client/resources/assets/velo-client"
OUT = ASSETS / "sounds/emote"
SR = 44100
RNG = np.random.default_rng(7)


# ---------------------------------------------------------------- building blocks

def t_(d):
    return np.arange(int(d * SR)) / SR


def env(d, a=0.005, decay=None, hold=0.0, release=0.05, curve=4.0):
    """Attack, optional hold, then an exponential decay (decay = time to -60 dB) or a release."""
    n = int(d * SR)
    t = np.arange(n) / SR
    e = np.minimum(1.0, t / max(a, 1e-4))
    if decay is not None:
        after = np.maximum(0.0, t - a - hold)
        e = e * np.exp(-after * 6.9 / decay)
    if release:
        e *= np.clip((d - t) / release, 0, 1) ** (curve / 4)
    return e


def sine(f, d, phase=0.0):
    f = np.broadcast_to(np.asarray(f, dtype=float), (int(d * SR),))
    return np.sin(2 * np.pi * np.cumsum(f) / SR + phase)


def saw(f, d):
    f = np.broadcast_to(np.asarray(f, dtype=float), (int(d * SR),))
    ph = np.cumsum(f) / SR
    # band-limited-ish: a few harmonics summed (enough for these short sounds, no aliasing whine)
    out = np.zeros_like(ph)
    for k in range(1, 24):
        mask = (f * k) < SR / 2.2
        out += mask * np.sin(2 * np.pi * k * ph) / k
    return out * 0.6


def square(f, d):
    f = np.broadcast_to(np.asarray(f, dtype=float), (int(d * SR),))
    ph = np.cumsum(f) / SR
    out = np.zeros_like(ph)
    for k in range(1, 30, 2):
        mask = (f * k) < SR / 2.2
        out += mask * np.sin(2 * np.pi * k * ph) / k
    return out * 0.8


def noise(d):
    return RNG.standard_normal(int(d * SR))


def lowpass(x, cutoff):
    """One-pole low pass; cutoff may be an array (a sweep)."""
    cutoff = np.broadcast_to(np.asarray(cutoff, dtype=float), x.shape)
    a = 1 - np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc += a[i] * (x[i] - acc)
        y[i] = acc
    return y


def lp(x, cutoff, order=2):
    for _ in range(order):
        x = lowpass(x, cutoff)
    return x


def hp(x, cutoff, order=1):
    for _ in range(order):
        x = x - lowpass(x, cutoff)
    return x


def bandpass(x, center, q=4.0):
    """A resonant band pass (state variable filter); center may sweep."""
    center = np.broadcast_to(np.asarray(center, dtype=float), x.shape)
    f = 2 * np.sin(np.pi * np.minimum(center, SR / 6) / SR)
    damp = 1.0 / q
    low = band = 0.0
    y = np.empty_like(x)
    for i in range(len(x)):
        high = x[i] - low - damp * band
        band += f[i] * high
        low += f[i] * band
        y[i] = band
    return y


def reverb(x, size=1.2, wet=0.25, tone=5000):
    ir_n = int(size * SR)
    ir = RNG.standard_normal(ir_n) * np.exp(-np.arange(ir_n) / SR * 6.9 / size)
    ir = lp(ir, tone, 1)
    ir /= np.sqrt(np.sum(ir ** 2))
    n = len(x) + ir_n
    y = np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(ir, n), n)
    out = np.concatenate([x, np.zeros(ir_n)]) * (1 - wet) + y * wet * 1.4
    # trim the silent tail
    alive = np.where(np.abs(out) > 1e-3 * np.max(np.abs(out)))[0]
    return out[: alive[-1] + 1] if len(alive) else out


def pad(x, before=0.0):
    return np.concatenate([np.zeros(int(before * SR)), x])


def mixdown(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[: len(p)] += p
    return out


def at(x, start, total=None):
    out = np.zeros(int((total or start + len(x) / SR) * SR) + 1)
    s = int(start * SR)
    end = min(len(out), s + len(x))
    out[s:end] += x[: end - s]
    return out


def bell(f, d, bright=1.0):
    """An inharmonic bell / glockenspiel tone."""
    parts = [(1.0, 1.0, 1.0), (2.76, 0.5 * bright, 0.6), (5.4, 0.25 * bright, 0.35), (8.93, 0.12 * bright, 0.22), (2.0, 0.2, 0.8)]
    out = np.zeros(int(d * SR))
    for ratio, amp, dec in parts:
        out += amp * sine(f * ratio, d) * env(d, 0.002, d * dec, release=0.02)
    return out


def drive(x, amount=2.0):
    return np.tanh(x * amount) / np.tanh(amount)


def norm(x, peak=0.89):
    m = np.max(np.abs(x))
    return x if m == 0 else x / m * peak


# ---------------------------------------------------------------- the sounds

def chime():
    notes = [1318.5, 1975.5]
    out = mixdown(*[at(bell(f, 1.6) * (0.9 - 0.2 * i), 0.06 * i) for i, f in enumerate(notes)])
    return reverb(out, 1.4, 0.3)


def sparkle():
    out = np.zeros(int(1.3 * SR))
    for i in range(9):
        f = 2200 * 2 ** (RNG.integers(0, 12) / 12)
        out = mixdown(out, at(bell(f, 0.5, 0.6) * (0.5 + 0.5 * RNG.random()), 0.04 + i * 0.055))
    return reverb(hp(out, 900), 1.2, 0.35, 8000)


def whoosh():
    d = 0.75
    t = t_(d)
    swell = np.sin(np.pi * np.clip(t / d, 0, 1)) ** 2
    x = bandpass(noise(d), 300 + 2600 * np.sin(np.pi * t / d) ** 1.5, 1.6) * swell
    return norm(x, 0.8)


def pop():
    d = 0.18
    body = sine(900 * np.exp(-t_(d) * 30) + 180, d) * env(d, 0.001, 0.08)
    click = hp(noise(d), 2000) * env(d, 0.0005, 0.012)
    return mixdown(body, click * 0.4)


def popper():
    """A party popper: a crack and a shower of paper."""
    d = 1.2
    crack = hp(noise(0.08), 600) * env(0.08, 0.0005, 0.05)
    boom = sine(150 * np.exp(-t_(0.25) * 12) + 60, 0.25) * env(0.25, 0.001, 0.2)
    rustle = np.zeros(int(d * SR))
    for _ in range(60):
        g = bandpass(noise(0.03), 4000 + 3000 * RNG.random(), 3) * env(0.03, 0.001, 0.02)
        rustle = mixdown(rustle, at(g * RNG.random() * 0.5, 0.05 + RNG.random() * 0.9))
    return reverb(mixdown(crack * 1.2, boom, rustle * 0.6), 0.9, 0.2)


def crackle():
    d = 1.6
    out = np.zeros(int(d * SR))
    for _ in range(90):
        when = (RNG.random() ** 0.7) * 1.4
        c = hp(noise(0.012), 1500) * env(0.012, 0.0003, 0.008)
        out = mixdown(out, at(c * (0.3 + RNG.random()), when))
    return reverb(out, 1.0, 0.3)


def stomp():
    d = 0.6
    sub = sine(110 * np.exp(-t_(d) * 9) + 38, d) * env(d, 0.001, 0.45)
    thud = lp(noise(d), 400) * env(d, 0.001, 0.12) * 2.5
    grit = bandpass(noise(0.1), 1500, 2) * env(0.1, 0.0005, 0.05)
    return norm(drive(mixdown(sub, thud, grit * 0.5), 1.6))


def boom():
    d = 2.2
    sub = sine(80 * np.exp(-t_(d) * 4) + 30, d) * env(d, 0.002, 1.6)
    rumble = lp(noise(d), 160 + 400 * np.exp(-t_(d) * 3)) * env(d, 0.002, 1.8) * 3
    crack = hp(noise(0.05), 1200) * env(0.05, 0.0005, 0.03)
    return norm(drive(reverb(mixdown(sub, rumble, crack * 0.6), 1.6, 0.25, 2500), 1.4))


def thunder():
    d = 3.2
    t = t_(d)
    crack = hp(noise(d), 1800) * env(d, 0.001, 0.25) * (1 + 0.8 * (RNG.random(len(t)) > 0.97))
    rumble = lp(noise(d), 120 + 300 * np.exp(-t * 1.5), 3) * env(d, 0.05, 2.8) * 6
    flutter = 0.7 + 0.3 * np.sin(2 * np.pi * 7 * t + 3 * np.sin(2 * np.pi * 0.7 * t))
    return norm(drive(reverb(mixdown(crack * 0.9, rumble * flutter), 2.0, 0.3, 3000), 1.3))


def zap():
    d = 0.7
    t = t_(d)
    f = 120 + 60 * RNG.standard_normal(len(t)).cumsum() / 400
    buzz = saw(np.abs(f) + 90, d) * (0.6 + 0.4 * (RNG.random(len(t)) > 0.5))
    buzz = bandpass(buzz, 1800, 1.5)
    crack = np.zeros(len(t))
    for _ in range(14):
        crack = mixdown(crack, at(hp(noise(0.006), 3000) * env(0.006, 0.0002, 0.004), RNG.random() * 0.6))[: len(t)]
    return norm(drive((buzz * 1.5 + crack) * env(d, 0.005, 0.6), 2.5), 0.8)


def power_up():
    d = 1.1
    t = t_(d)
    f = 180 * 2 ** (t / d * 2.2) * (1 + 0.01 * np.sin(2 * np.pi * 9 * t))
    x = mixdown(square(f, d) * 0.5, saw(f * 1.005, d) * 0.5)
    x = lp(x, 900 + 5000 * t / d) * env(d, 0.02, None, release=0.12)
    return reverb(norm(x, 0.7), 0.8, 0.25)


def magic():
    """A rising shimmer for bows and spells."""
    d = 2.2
    t = t_(d)
    pad_ = sum(sine(f * (1 + 0.003 * np.sin(2 * np.pi * (0.5 + k) * t)), d) / (k + 1)
               for k, f in enumerate([523.3, 659.3, 784.0, 1046.5]))
    pad_ *= np.sin(np.pi * np.clip(t / d, 0, 1)) ** 1.5
    sh = sparkle()
    return reverb(norm(mixdown(pad_ * 0.35, at(sh * 0.5, 0.3)), 0.8), 1.8, 0.35)


def heart():
    """Soft, cute: a two-note pluck with a warm chord."""
    out = np.zeros(1)
    for i, f in enumerate([659.3, 987.8]):
        pl = sine(f, 0.9) * env(0.9, 0.003, 0.5) + 0.3 * sine(2 * f, 0.9) * env(0.9, 0.002, 0.2)
        out = mixdown(out, at(pl, i * 0.12))
    chord = sum(sine(f, 1.2) for f in (329.6, 415.3, 493.9)) * env(1.2, 0.08, 0.9) * 0.12
    return reverb(mixdown(out, at(chord, 0.1)), 1.5, 0.35)


def boing():
    d = 0.7
    t = t_(d)
    f = 220 + 160 * np.sin(2 * np.pi * 11 * t) * np.exp(-t * 5) + 120 * np.exp(-t * 6)
    return norm(lp(saw(f, d), 2500) * env(d, 0.003, 0.6) + sine(f, d) * env(d, 0.003, 0.6), 0.8)


def tsk():
    """Two tongue clicks: tsk tsk."""
    out = np.zeros(1)
    for i in range(2):
        c = bandpass(noise(0.05), 3200, 5) * env(0.05, 0.0005, 0.02) + bandpass(noise(0.05), 1200, 3) * env(0.05, 0.0005, 0.01)
        out = mixdown(out, at(c, i * 0.22))
    return norm(reverb(out, 0.3, 0.12))


def slap():
    d = 0.35
    smack = hp(noise(d), 1200) * env(d, 0.0005, 0.06)
    body = lp(noise(d), 600) * env(d, 0.001, 0.1) * 1.5
    tone = sine(220 * np.exp(-t_(d) * 20) + 90, d) * env(d, 0.001, 0.08)
    return norm(reverb(mixdown(smack, body, tone * 0.6), 0.4, 0.15))


def slurp():
    d = 1.2
    t = t_(d)
    gate = (np.sin(2 * np.pi * (14 + 6 * t) * t) > 0.2) * 1.0
    gate = lp(gate, 60)
    x = bandpass(noise(d), 900 + 900 * t / d + 300 * np.sin(2 * np.pi * 9 * t), 6) * gate
    x *= env(d, 0.05, None, release=0.15)
    return norm(x, 0.7)


def clink():
    return reverb(mixdown(bell(2637, 0.8, 1.4), at(bell(3520, 0.6, 1.0) * 0.4, 0.004)), 0.6, 0.2)


def snore():
    d = 3.0
    t = t_(d)
    inhale = np.clip(np.sin(np.pi * t / 1.6), 0, 1) * (t < 1.6)
    buzz = saw(62 + 6 * np.sin(2 * np.pi * 0.6 * t), d)
    rattle = 0.6 + 0.4 * np.sin(2 * np.pi * 31 * t)
    snr = lp(bandpass(buzz * rattle, 500, 2) + 0.4 * lp(noise(d), 700), 1500) * inhale
    exhale_t = np.clip(t - 1.8, 0, None)
    exhale = lp(noise(d), 900) * np.sin(np.pi * np.clip(exhale_t / 1.0, 0, 1)) * (t > 1.8) * 0.35
    whistle = sine(1400 - 300 * exhale_t, d) * np.sin(np.pi * np.clip(exhale_t / 1.0, 0, 1)) * (t > 1.8) * 0.08
    return norm(mixdown(snr, exhale, whistle), 0.75)


def airhorn():
    """The air horn: a brassy, slightly sour chord, hard attack, driven hot."""
    d = 0.9
    t = t_(d)
    wob = 1 + 0.004 * np.sin(2 * np.pi * 6 * t)
    x = sum(saw(f * wob, d) for f in (466.2, 587.3, 698.5, 349.2))
    x = bandpass(x, 1400, 0.9) + lp(x, 3000) * 0.4
    x *= env(d, 0.008, None, release=0.06)
    return norm(drive(x, 3.5), 0.92)


def hitmarker():
    d = 0.08
    return norm(hp(noise(d), 2500) * env(d, 0.0003, 0.02) + sine(3000, d) * env(d, 0.0005, 0.03) * 0.5)


def whistle():
    """A referee's whistle: a pea trill."""
    d = 0.9
    t = t_(d)
    trill = 0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 32 * t))
    tone = sine(2900 + 120 * trill, d) * (0.7 + 0.3 * trill) + 0.15 * bandpass(noise(d), 2900, 4)
    return norm(tone * env(d, 0.01, None, release=0.08), 0.7)


def stadium_horn():
    d = 1.6
    t = t_(d)
    x = sum(saw(f * (1 + 0.003 * np.sin(2 * np.pi * 5 * t)), d) for f in (146.8, 185.0, 220.0))
    x = lp(x, 1400) * env(d, 0.05, None, release=0.3)
    return reverb(norm(drive(x, 1.6), 0.8), 1.4, 0.3, 3000)


def kick_ball():
    d = 0.3
    thump = sine(160 * np.exp(-t_(d) * 25) + 70, d) * env(d, 0.001, 0.15)
    rubber = bandpass(noise(d), 700, 3) * env(d, 0.0005, 0.05)
    return norm(mixdown(thump, rubber * 0.8))


def growl():
    """A zombie's groan."""
    d = 2.0
    t = t_(d)
    f = 70 + 15 * np.sin(2 * np.pi * 0.8 * t) + 8 * RNG.standard_normal(len(t)).cumsum() / 900
    src = saw(np.abs(f) + 40, d) * (0.7 + 0.3 * lp(np.abs(noise(d)), 30))
    vowel = bandpass(src, 500 + 200 * np.sin(np.pi * t / d), 3) + bandpass(src, 1100, 4) * 0.5 + 0.15 * lp(noise(d), 900)
    return reverb(norm(drive(vowel * env(d, 0.25, None, release=0.5), 1.8), 0.85), 1.6, 0.3, 2500)


def ghost():
    d = 2.6
    t = t_(d)
    f = 520 + 140 * np.sin(np.pi * t / d) + 18 * np.sin(2 * np.pi * 5.5 * t)
    w = sine(f, d) + 0.2 * sine(f * 2, d) + 0.05 * bandpass(noise(d), 1200, 2)
    return reverb(norm(w * np.sin(np.pi * np.clip(t / d, 0, 1)) ** 1.2, 0.6), 2.2, 0.45)


def organ_sting():
    """A spooky organ chord, then a falling minor run."""
    d = 2.4
    out = np.zeros(1)

    def organ(f, dd):
        tt = t_(dd)
        trem = 1 + 0.06 * np.sin(2 * np.pi * 6 * tt)
        return sum(a * sine(f * h, dd) for h, a in ((0.5, 0.4), (1, 1), (2, 0.6), (3, 0.3), (4, 0.2))) * trem * env(dd, 0.02, None, release=0.2)
    chord = sum(organ(f, 1.3) for f in (146.8, 174.6, 220.0, 277.2)) * 0.25
    out = mixdown(out, chord)
    for i, f in enumerate([587.3, 554.4, 523.3, 440.0]):
        out = mixdown(out, at(organ(f, 0.3) * 0.25, 1.2 + i * 0.22))
    return reverb(norm(out, 0.85), 2.4, 0.4)


def bats():
    d = 1.6
    t = t_(d)
    flap = 0.5 + 0.5 * np.sin(2 * np.pi * 22 * t + 2 * np.sin(2 * np.pi * 3 * t))
    x = bandpass(noise(d), 1100, 1.5) * flap ** 3
    squeaks = np.zeros(len(t))
    for _ in range(6):
        sq = sine(6000 + 1500 * RNG.random() - 2000 * t_(0.05) / 0.05, 0.05) * env(0.05, 0.002, 0.04)
        squeaks = mixdown(squeaks, at(sq * 0.3, RNG.random() * 1.3))[: len(t)]
    return norm((x + squeaks) * np.sin(np.pi * np.clip(t / d, 0, 1)), 0.75)


def grind():
    """A stone slab grinding up out of the earth."""
    d = 1.8
    t = t_(d)
    grain = lp(noise(d) * (0.5 + 0.5 * (RNG.random(len(t)) > 0.92)), 1200) * 2
    rumble = lp(noise(d), 140, 3) * 6
    return norm(drive((grain + rumble) * np.sin(np.pi * np.clip(t / d, 0, 1)) ** 0.7, 1.5), 0.8)


def splat():
    d = 0.5
    crunch = lp(noise(d) * (RNG.random(int(d * SR)) > 0.4), 3500) * env(d, 0.001, 0.25)
    thump = sine(140 * np.exp(-t_(d) * 20) + 60, d) * env(d, 0.001, 0.1)
    return norm(mixdown(crunch, thump * 0.7))


def sleigh_bells():
    d = 1.4
    out = np.zeros(int(d * SR))
    for k in range(4):
        base = 0.08 + k * 0.3
        for _ in range(14):
            f = 3000 + 2500 * RNG.random()
            out = mixdown(out, at(bell(f, 0.35, 1.6) * 0.25, base + RNG.random() * 0.12))
    return reverb(hp(out, 1500), 1.0, 0.3)


def fanfare():
    """Brass: up the major arpeggio into a held chord."""
    out = np.zeros(1)

    def brass(f, dd):
        tt = t_(dd)
        x = saw(f * (1 + 0.004 * np.sin(2 * np.pi * 5 * tt)), dd)
        return lp(x, 600 + 3000 * env(dd, 0.04, 0.6, release=0.1)) * env(dd, 0.02, None, release=0.15)
    for i, f in enumerate([392.0, 523.3, 659.3]):
        out = mixdown(out, at(brass(f, 0.22) * 0.6, i * 0.14))
    chord = sum(brass(f, 1.5) for f in (523.3, 659.3, 784.0, 1046.5)) * 0.35
    out = mixdown(out, at(chord, 0.42))
    return reverb(norm(drive(out, 1.3), 0.85), 1.6, 0.3)


def sad_trombone():
    out = np.zeros(1)
    notes = [(293.7, 0.45), (277.2, 0.45), (261.6, 0.45), (246.9, 1.5)]
    pos = 0.0
    for i, (f, dd) in enumerate(notes):
        tt = t_(dd)
        wob = 1 + (0.03 * np.sin(2 * np.pi * 6 * tt) if i == 3 else 0.004 * np.sin(2 * np.pi * 5 * tt))
        x = lp(saw(f * wob, dd), 1400) * env(dd, 0.03, None, release=0.12)
        out = mixdown(out, at(x * 0.7, pos))
        pos += dd + 0.04
    return reverb(norm(drive(out, 1.4), 0.8), 1.0, 0.2)


def bass_drop():
    d = 1.6
    t = t_(d)
    f = 30 + 170 * np.exp(-t * 2.2)
    x = sine(f, d) + 0.3 * square(f, d)
    return norm(drive(lp(x, 900) * env(d, 0.01, 1.4), 2.0), 0.9)


def laser():
    d = 0.35
    t = t_(d)
    f = 2400 * np.exp(-t * 9) + 300
    return norm(square(f, d) * env(d, 0.002, 0.25) * 0.6 + sine(f * 0.5, d) * env(d, 0.002, 0.2) * 0.4, 0.6)


def tap():
    d = 0.12
    return norm(bandpass(noise(d), 2400, 3) * env(d, 0.0005, 0.03) + sine(600, d) * env(d, 0.001, 0.04) * 0.4, 0.6)


def dust():
    d = 1.0
    t = t_(d)
    return norm(lp(noise(d), 700 + 900 * np.exp(-t * 3)) * env(d, 0.005, 0.8), 0.6)


def drumroll():
    d = 1.4
    t = t_(d)
    hits = np.zeros(len(t))
    k = 0.0
    while k < d - 0.05:
        hits = mixdown(hits, at(bandpass(noise(0.05), 2000, 1.2) * env(0.05, 0.0005, 0.03) * (0.4 + 0.6 * k / d), k))[: len(t)]
        k += 0.045
    return norm(hits, 0.6)


SOUNDS = {
    "chime": chime, "sparkle": sparkle, "whoosh": whoosh, "pop": pop, "popper": popper, "crackle": crackle,
    "stomp": stomp, "boom": boom, "thunder": thunder, "zap": zap, "power_up": power_up, "magic": magic, "heart": heart,
    "boing": boing, "tsk": tsk, "slap": slap, "slurp": slurp, "clink": clink, "snore": snore, "airhorn": airhorn,
    "hitmarker": hitmarker, "whistle": whistle, "stadium_horn": stadium_horn, "kick_ball": kick_ball, "growl": growl,
    "ghost": ghost, "organ_sting": organ_sting, "bats": bats, "grind": grind, "splat": splat, "sleigh_bells": sleigh_bells,
    "fanfare": fanfare, "sad_trombone": sad_trombone, "bass_drop": bass_drop, "laser": laser, "tap": tap, "dust": dust,
    "drumroll": drumroll,
}


def loudness(x, target=0.2, peak=0.89):
    """Even loudness: the loudest 300 ms window at the same RMS for every sound (peaks capped)."""
    w = int(0.3 * SR)
    power = np.convolve(x ** 2, np.ones(w) / w, mode="same") if len(x) > w else np.array([np.mean(x ** 2)])
    rms = np.sqrt(np.max(power))
    if rms > 0:
        x = x * (target / rms)
    m = np.max(np.abs(x))
    return x * (peak / m) if m > peak else x


def write_ogg(x, path, quality=4):
    path.parent.mkdir(parents=True, exist_ok=True)
    x = np.clip(x, -1, 1)
    # fade the last 5 ms so nothing clicks
    n = min(len(x), int(0.005 * SR))
    x[-n:] *= np.linspace(1, 0, n)
    with tempfile.NamedTemporaryFile(suffix=".wav") as tmp:
        with wave.open(tmp.name, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((x * 32767).astype("<i2").tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-ac", "1", "-c:a", "libvorbis", "-q:a", str(quality),
                        str(path)], check=True)


def update_sounds_json(entries):
    path = ASSETS / "sounds.json"
    data = json.loads(path.read_text()) if path.exists() else {}
    data.update(entries)
    path.write_text(json.dumps(dict(sorted(data.items())), indent=1) + "\n")


def main():
    names = [a for a in sys.argv[1:] if not a.startswith("--")] or list(SOUNDS)
    entries = {}
    for name in names:
        x = loudness(SOUNDS[name]())
        write_ogg(x, OUT / f"{name}.ogg")
        entries[f"emote.{name}"] = {"sounds": [{"name": f"velo-client:emote/{name}"}]}
        print(f"{name:14s} {len(x) / SR:5.2f}s  {(OUT / f'{name}.ogg').stat().st_size // 1024} KB")
    update_sounds_json(entries)


if __name__ == "__main__":
    main()
