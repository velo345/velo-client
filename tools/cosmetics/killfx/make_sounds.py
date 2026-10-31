#!/usr/bin/env python3
"""Synthesises the Store kill effects' sounds (cosmetics/killfx/KillFxEffects - no vanilla sounds, no samples) as
mono Ogg Vorbis in assets/velo-client/sounds/killfx/<id>.ogg plus their sounds.json entries ("velo-client:killfx.<id>"),
an MP3 copy for the website (velo-site/assets/killfx) and a small WAV for the launcher (Java Sound plays no MP3).

    python3 tools/cosmetics/killfx/make_sounds.py [id ...]

Each sound's big hit lands on its effect's timeline mark (orbital strike 0.38 s, black hole bang 1.75 s, supernova
0.45 s, frostbite shatter 1.3 s, reaper chains 0.95 s). Uses the emote sounds' building blocks.
"""
import importlib.util
import subprocess
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("emote_sounds", HERE.parent / "emotes" / "make_sounds.py")
E = importlib.util.module_from_spec(spec)
spec.loader.exec_module(E)

ROOT = HERE.parents[2]
OUT = E.ASSETS / "sounds/killfx"
SITE = ROOT / "velo-site/assets/killfx"
LAUNCHER = ROOT / "launcher/src/main/resources/net/veloclient/launcher/killfx"
SR = E.SR
RNG = np.random.default_rng(23)
t_, env, sine, saw, square, noise = E.t_, E.env, E.sine, E.saw, E.square, E.noise
lp, hp, bandpass, reverb, mixdown, at, bell, drive, norm = E.lp, E.hp, E.bandpass, E.reverb, E.mixdown, E.at, E.bell, E.drive, E.norm


def thump(d=0.7, f0=120, f1=34, decay=0.5):
    """A deep body hit."""
    sub = sine(f0 * np.exp(-t_(d) * 10) + f1, d) * env(d, 0.001, decay)
    body = lp(noise(d), 300) * env(d, 0.001, 0.1) * 2
    return mixdown(sub, body)


def crack(d=0.06, cutoff=1500):
    return hp(noise(d), cutoff) * env(d, 0.0004, d * 0.6)


def explosion(d=2.4, size=1.0):
    sub = sine(70 * np.exp(-t_(d) * 3.5) + 28, d) * env(d, 0.002, 1.5 * size)
    rumble = lp(noise(d), 180 + 600 * np.exp(-t_(d) * 2.5), 2) * env(d, 0.002, 1.9 * size) * 3.5
    return drive(mixdown(sub, rumble, crack(0.08, 1000) * 0.9), 1.8)


def glass(d=1.8, count=55, low=2200, high=7500):
    """Breaking glass / crystal: a crack, then many tiny bright tinkles scattering and settling."""
    out = crack(0.09, 2500) * 1.2
    for _ in range(count):
        when = (RNG.random() ** 1.8) * (d - 0.4)
        f = low + (high - low) * RNG.random()
        g = bell(f, 0.25 + 0.3 * RNG.random(), 1.3) * (0.15 + 0.6 * RNG.random()) * np.exp(-when * 1.2)
        out = mixdown(out, at(g, when))
    for _ in range(40):
        when = (RNG.random() ** 1.5) * (d - 0.3)
        g = bandpass(noise(0.02), 5000 + 4000 * RNG.random(), 4) * env(0.02, 0.0005, 0.015)
        out = mixdown(out, at(g * 0.6 * RNG.random(), when))
    return out


def swish(d=0.28, f0=6000, f1=500):
    t = t_(d)
    return bandpass(noise(d), f0 * (f1 / f0) ** (t / d), 2.2) * np.sin(np.pi * np.clip(t / d, 0, 1)) ** 1.5


# ---------------------------------------------------------------- the effects

def shatter():
    return reverb(mixdown(thump(0.6, 140, 40, 0.35) * 0.9, glass(2.0, 70) * 1.1), 1.3, 0.28, 9000)


def orbital():
    strike = 0.38
    # The lock-on: a whine rising to the strike, a beeping target.
    d = strike
    t = t_(d)
    whine = sine(500 * 2 ** (t / d * 2.4), d) * (0.6 + 0.4 * np.sin(2 * np.pi * 28 * t)) * (t / d) ** 1.5
    beeps = mixdown(*[at(sine(1900, 0.04) * env(0.04, 0.002, 0.03), i * 0.09) for i in range(4)])
    # The beam: a huge blast, a bright laser roar sweeping down, crackling as it cools.
    db = 2.6
    tb = t_(db)
    laser = bandpass(saw(140 + 60 * np.sin(2 * np.pi * 5 * tb), db) + noise(db) * 0.6, 3500 * np.exp(-tb * 1.6) + 400, 1.4) * env(db, 0.003, 1.1)
    sizzle = np.zeros(len(tb))
    for _ in range(80):
        sizzle = mixdown(sizzle, at(crack(0.008, 3000) * RNG.random() * 0.6, 0.2 + RNG.random() ** 0.6 * 1.8))[: len(tb)]
    blast = mixdown(explosion(2.6, 1.0), laser * 1.4, sizzle)
    return reverb(mixdown(whine * 0.35, beeps * 0.3, at(blast, strike)), 1.8, 0.3, 4000)


def blackhole():
    bang = 1.75
    d = 1.6
    t = t_(d)
    # A deep drone opening up, warbling, with air rushing in (rising noise).
    drone = lp(saw(42 + 6 * t + 3 * np.sin(2 * np.pi * 0.9 * t), d) + saw(42.7 + 6 * t, d), 120 + 700 * (t / d) ** 2, 2) * env(d, 0.15, None, release=0.08)
    rush = bandpass(noise(d), 300 + 3500 * (t / d) ** 2, 1.5) * (t / d) ** 2.2
    wobble = 0.7 + 0.3 * np.sin(2 * np.pi * (3 + 9 * t / d) * t)
    swell = mixdown(drone * 0.55 * wobble, rush * 0.4)
    # The collapse: the drone falls away, a heartbeat of silence, then the bang with a shimmer.
    dc = 0.15
    fall = sine(140 * np.exp(-t_(dc) * 25) + 30, dc) * env(dc, 0.001, 0.15)
    shimmer = mixdown(*[at(bell(f, 1.4, 1.4) * 0.25, 0.02 * i) for i, f in enumerate([1568, 2349, 3136, 4186])])
    bang_sound = mixdown(explosion(2.2, 0.9), crack(0.05, 2000) * 1.5, shimmer)
    return reverb(mixdown(swell, at(fall, 1.6), at(bang_sound, bang)), 1.6, 0.3, 5000)


def raptor_cry(d, f0, f1, f2, rasp=70):
    """A hawk / eagle scream: a raspy high call that snaps up to {@code f1} and falls away to {@code f2}."""
    t = t_(d)
    rise = np.clip(t / 0.07, 0, 1)
    fall = np.clip((t - 0.07) / (d - 0.07), 0, 1) ** 0.8
    pitch = np.where(t < 0.07, f0 + (f1 - f0) * rise, f1 + (f2 - f1) * fall)
    pitch = pitch * (1 + 0.012 * np.sin(2 * np.pi * 9 * t))
    voice = sine(pitch, d) + 0.55 * sine(pitch * 2.01, d) + 0.3 * sine(pitch * 3.02, d) + 0.12 * sine(pitch * 0.5, d)
    voice = voice * (0.65 + 0.35 * np.sin(2 * np.pi * rasp * t))  # the rasp
    breath = bandpass(noise(d), pitch * 1.1, 2.5) * 0.35
    shape = np.clip(t / 0.03, 0, 1) * np.clip((d - t) / (d * 0.55), 0, 1) ** 1.3
    return hp(mixdown(voice, breath) * shape, 900)


def phoenix():
    rise_at = 0.45
    # The fire column roaring up out of the body: a rushing, rising roar that peaks as the bird breaks out.
    d = 0.6
    t = t_(d)
    roar = mixdown(lp(noise(d), 300 + 2200 * (t / d) ** 1.5, 2) * 2.2, hp(lp(noise(d), 4000), 1200) * 0.5 * (t / d)) * (0.2 + 0.8 * (t / d) ** 1.4)
    fire = np.zeros(int(3.0 * SR))
    for _ in range(220):
        fire = mixdown(fire, at(crack(0.008, 2200) * RNG.random() * 0.45, RNG.random() ** 0.7 * 2.6))
    burst = mixdown(thump(0.7, 130, 40, 0.45), crack(0.08, 900) * 1.2,
                    lp(noise(1.4), 900, 2) * env(1.4, 0.005, 1.1) * 1.4)
    # The cries: one long as it bursts out, a shorter one higher up.
    cry1 = raptor_cry(1.0, 2300, 3300, 1900)
    cry2 = raptor_cry(0.6, 2500, 3500, 2300, rasp=85) * 0.6
    # Great wing beats, in time with the animation (every 0.74 s).
    flaps = mixdown(*[at(lp(noise(0.22), 380, 2) * env(0.22, 0.03, 0.15) * 2.2, rise_at + 0.18 + i * 0.74) for i in range(3)])
    # Bursting into sparks at the end.
    sparks = np.zeros(int(0.9 * SR))
    for _ in range(60):
        sparks = mixdown(sparks, at(mixdown(bell(4000 + 4000 * RNG.random(), 0.2, 2) * 0.12, crack(0.006, 3500) * 0.3 * RNG.random()), RNG.random() * 0.6))
    out = mixdown(roar * 0.9, fire * 0.8, at(burst, rise_at), at(cry1 * 0.55, rise_at + 0.04), at(cry2, rise_at + 1.0), flaps * 0.8,
                  at(sparks, 2.25))
    return reverb(out, 1.8, 0.3, 7000)


def reaper():
    slashes = mixdown(swish(0.26, 7000, 600) * 1.3, at(swish(0.26, 6000, 500) * 1.1, 0.13))
    hit = thump(0.5, 90, 34, 0.35) * 0.8
    # The wail: a ghostly voice rising and fading, vibrato and a hollow formant.
    d = 2.2
    t = t_(d)
    f = 440 * 2 ** (np.clip(t / 0.7, 0, 1) * 0.6 - np.clip((t - 1.0) / 1.2, 0, 1) * 0.9) * (1 + 0.025 * np.sin(2 * np.pi * 6 * t))
    voice = bandpass(saw(f, d), 900, 2.5) * 0.7 + sine(f, d) * 0.5 + sine(f * 2.01, d) * 0.15
    wail = voice * env(d, 0.25, None, release=0.9)
    # Chains snapping.
    clinks = mixdown(*[at(bell(2600 + 900 * RNG.random(), 0.35, 1.6) * (0.5 + 0.4 * RNG.random()), 0.95 + 0.025 * i) for i in range(6)])
    snap = crack(0.04, 2500) * 1.2
    return reverb(mixdown(slashes, hit, at(wail * 0.45, 0.3), clinks * 0.6, at(snap, 0.95)), 2.2, 0.38, 4500)


def glitch():
    d = 1.6
    out = np.zeros(int(d * SR))
    when = 0.0
    while when < 1.4:
        seg = 0.02 + RNG.random() * 0.09
        f = 80 * 2 ** (RNG.random() * 5)
        kind = RNG.random()
        if kind < 0.45:
            x = square(f, seg)
        elif kind < 0.75:
            x = saw(f, seg)
        else:
            x = hp(noise(seg), 1500)
        # Bit crush and sample-and-hold for the digital grit.
        hold = int(1 + RNG.random() * 12)
        x = np.repeat(x[::hold], hold)[: int(seg * SR)]
        x = np.round(x * 6) / 6
        amp = (0.25 + 0.55 * RNG.random()) * max(0.15, 1 - when / 1.5)
        out = mixdown(out, at(x * env(seg, 0.001, None, release=0.005) * amp, when))
        when += seg * (0.6 + RNG.random() * 1.2) + (0.03 if RNG.random() < 0.25 else 0)
    zap = E.zap()[: int(0.4 * SR)]
    return reverb(mixdown(out[: int(d * SR)], zap * 0.7, thump(0.4, 160, 50, 0.25) * 0.6), 0.8, 0.18, 7000)


def supernova():
    boom = 0.45
    d = boom
    t = t_(d)
    rise = mixdown(saw(220 * 2 ** (t / d * 3), d) * 0.4, bandpass(noise(d), 500 + 6000 * (t / d) ** 2, 1.5) * 0.8) * (t / d) ** 1.6
    shimmer = mixdown(*[at(bell(f, 2.4, 1.2) * 0.22, 0.03 * i) for i, f in enumerate([1046, 1568, 2093, 2637, 3136])])
    big = mixdown(explosion(3.2, 1.4), crack(0.1, 800) * 1.4, shimmer)
    return reverb(mixdown(lp(rise, 7000), at(big, boom)), 2.6, 0.35, 3500)


def frostbite():
    shatter_at = 1.3
    d = 1.3
    t = t_(d)
    # Ice forming: a cold rising whoosh and crackling that tightens up.
    freeze = bandpass(noise(d), 1500 + 5000 * (t / d), 2) * (0.3 + 0.7 * t / d) * env(d, 0.02, None, release=0.05)
    creaks = np.zeros(len(t))
    for _ in range(70):
        w = RNG.random() ** 0.7 * 1.25
        creaks = mixdown(creaks, at(bandpass(noise(0.015), 3000 + 5000 * RNG.random(), 5) * env(0.015, 0.0004, 0.01) * RNG.random(), w))[: len(t)]
    spikes = mixdown(*[at(thump(0.25, 220, 70, 0.12) * 0.35, 0.03 * i) for i in range(5)])
    smash = mixdown(thump(0.6, 130, 40, 0.35) * 0.9, glass(1.6, 60, 2600, 9000) * 1.2)
    return reverb(mixdown(freeze * 0.5, creaks * 0.9, spikes, at(smash, shatter_at)), 1.5, 0.3, 9000)


def meteor():
    hit = 0.5
    d = hit
    t = t_(d)
    # The whistle coming down, the roar of it burning through the air.
    whistle = sine(2400 * 2 ** (-t / d * 1.6), d) * (t / d) ** 1.2 * 0.5
    roar = bandpass(noise(d), 600 + 1500 * (t / d), 1.2) * (t / d) ** 1.5
    rocks = np.zeros(int(2.2 * SR))
    for _ in range(30):
        rocks = mixdown(rocks, at(lp(noise(0.05), 1200) * env(0.05, 0.001, 0.04) * RNG.random() * 0.6, 0.4 + RNG.random() ** 0.7 * 1.4))
    impact = mixdown(explosion(2.8, 1.3), crack(0.1, 700) * 1.5, rocks)
    return reverb(mixdown(whistle, roar * 0.8, at(impact, hit)), 1.9, 0.3, 3500)


def tornado():
    d = 2.9
    t = t_(d)
    shape = np.clip(t / 0.35, 0, 1) * np.clip((d - t) / 0.8, 0, 1)
    # The roar of the wind, its pitch and strength swirling.
    swirl = 0.7 + 0.3 * np.sin(2 * np.pi * 1.6 * t) * np.sin(2 * np.pi * 0.45 * t + 1)
    roar = lp(noise(d), 450 + 250 * np.sin(2 * np.pi * 0.8 * t), 2) * 2.4
    hiss = hp(lp(noise(d), 3500), 700) * 0.6
    rumble = lp(noise(d), 110, 2) * 3.0
    # Gusts whipping round, one every 0.35 s.
    gusts = mixdown(*[at(swish(0.45, 2600, 500) * 0.9, 0.2 + i * 0.35) for i in range(6)])
    debris = np.zeros(len(t))
    for _ in range(70):
        debris = mixdown(debris, at(lp(noise(0.03), 1600) * env(0.03, 0.001, 0.02) * RNG.random() * 0.7, 0.3 + RNG.random() * 1.6))[: len(t)]
    # Blowing apart at the end: one big gust outwards.
    blow = swish(0.8, 3000, 300) * 1.4
    wind = mixdown(roar * swirl, hiss * swirl, rumble) * shape
    return reverb(mixdown(wind, gusts, debris, at(blow, 1.9), thump(0.5, 100, 36, 0.3) * 0.6), 1.3, 0.22, 4000)


def grave():
    # Earth breaking, the stone grinding up, then a slow funeral bell.
    rumble = lp(noise(0.8), 200, 2) * env(0.8, 0.02, 0.6) * 2.5
    grind = bandpass(noise(0.6), 350, 3) * env(0.6, 0.05, 0.4) * (1 + 0.5 * np.sin(2 * np.pi * 30 * t_(0.6)))
    toll = bell(220, 3.2, 0.8) * 0.8 + bell(110, 3.2, 0.4) * 0.4
    land = thump(0.4, 90, 40, 0.25)
    return reverb(mixdown(rumble, at(grind * 0.8, 0.1), at(land, 0.55), at(toll, 0.65)), 2.4, 0.35, 3500)


def thunder():
    strikes = [0.0, 0.22, 0.5]
    out = np.zeros(int(3.4 * SR))
    for i, s in enumerate(strikes):
        snap = crack(0.06, 2500) * (1.5 - 0.3 * i)
        zz = E.zap()[: int(0.3 * SR)] * 0.5
        boom = lp(noise(2.2), 140 + 400 * np.exp(-t_(2.2) * 2), 3) * env(2.2, 0.01, 1.8) * (4 - i)
        out = mixdown(out, at(mixdown(snap, zz, boom), s))
    return reverb(drive(out, 1.4), 2.0, 0.3, 3000)


def shadow():
    # A deep rush out of the ground, a low growl, then everything dragged under.
    d = 2.2
    t = t_(d)
    rush = bandpass(noise(0.7), 200 + 900 * np.exp(-t_(0.7) * 3), 1.2) * env(0.7, 0.01, 0.5) * 1.4
    drone = lp(saw(38 + 4 * np.sin(2 * np.pi * 0.5 * t), d) + saw(38.6, d), 220, 2) * env(d, 0.1, None, release=0.6) * 1.2
    growl = bandpass(saw(70 + 15 * np.sin(2 * np.pi * 9 * t[: int(1.2 * SR)]), 1.2), 500, 3) * env(1.2, 0.2, None, release=0.5)
    whispers = mixdown(*[at(bandpass(noise(0.4), 2500 + 1500 * RNG.random(), 6) * env(0.4, 0.1, None, release=0.2) * 0.25, 0.3 + 0.25 * i) for i in range(5)])
    suck = bandpass(noise(0.5), 3000 * np.exp(-t_(0.5) * 5) + 150, 1.5) * env(0.5, 0.2, None, release=0.1)
    return reverb(mixdown(thump(0.7, 80, 30, 0.5), rush, drone * 0.8, at(growl * 0.6, 0.3), whispers, at(suck, 1.5)), 2.0, 0.35, 3000)


def katana():
    shings = np.zeros(int(1.4 * SR))
    for i in range(3):
        cut = swish(0.12, 9000, 1500) * 1.3
        ring = bell(3200 + 400 * i, 0.9, 1.8) * 0.35
        shings = mixdown(shings, at(mixdown(cut, ring), i * 0.1))
    burst = bandpass(noise(0.8), 3000, 1.2) * env(0.8, 0.01, 0.6) * 0.5
    sheath = mixdown(swish(0.15, 3000, 800) * 0.6, at(mixdown(bell(2600, 0.6, 1.5) * 0.3, crack(0.02, 3000) * 0.6), 0.12))
    return reverb(mixdown(shings, at(burst, 0.34), at(sheath, 0.55)), 1.6, 0.3, 8000)


def jackpot():
    # Three slot dings for 7-7-7, then a win jingle and coins clattering down.
    dings = mixdown(*[at(bell(f, 0.6, 1.2) * 0.7, 0.05 + 0.1 * i) for i, f in enumerate([1568, 1568, 1568])])
    jingle = mixdown(*[at(mixdown(bell(f, 0.7, 1.0) * 0.5, square(f, 0.12) * env(0.12, 0.002, 0.1) * 0.15), 0.32 + 0.07 * i)
                       for i, f in enumerate([1047, 1319, 1568, 2093, 2637])])
    coins = np.zeros(int(2.4 * SR))
    for _ in range(70):
        f = 3500 + 3000 * RNG.random()
        coins = mixdown(coins, at(bell(f, 0.18, 2) * (0.15 + 0.3 * RNG.random()), 0.4 + RNG.random() ** 0.8 * 1.8))
    return reverb(mixdown(dings, jingle, coins), 1.4, 0.28, 9000)


SOUNDS = {"shatter": shatter, "orbital": orbital, "blackhole": blackhole, "phoenix": phoenix, "reaper": reaper,
          "glitch": glitch, "supernova": supernova, "frostbite": frostbite, "meteor": meteor, "tornado": tornado,
          "grave": grave, "thunder": thunder, "shadow": shadow, "katana": katana, "jackpot": jackpot}


def write_mp3(ogg, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(ogg), "-c:a", "libmp3lame", "-q:a", "4", str(path)], check=True)


def write_wav(ogg, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(ogg), "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le", str(path)], check=True)


def main():
    names = [a for a in sys.argv[1:] if not a.startswith("--")] or list(SOUNDS)
    entries = {}
    for name in names:
        x = E.loudness(SOUNDS[name](), target=0.24)
        ogg = OUT / f"{name}.ogg"
        E.write_ogg(x, ogg)
        write_mp3(ogg, SITE / f"{name}.mp3")
        write_wav(ogg, LAUNCHER / f"{name}.wav")
        entries[f"killfx.{name}"] = {"sounds": [{"name": f"velo-client:killfx/{name}"}]}
        print(f"{name:10s} {len(x) / SR:5.2f}s  {ogg.stat().st_size // 1024} KB")
    E.update_sounds_json(entries)


if __name__ == "__main__":
    main()
