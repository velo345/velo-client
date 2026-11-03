#!/usr/bin/env python3
"""Synthesises Velo's interface sounds and the calm title-screen track - no samples, no vanilla sounds.

    python3 tools/sounds/make_ui_sounds.py [name ...] [--music]

Game: assets/velo-client/sounds/ui/<name>.ogg + sounds.json entries "velo-client:ui.<name>" (played through
client/sound/VeloUiSounds at a fixed volume, so the files carry their own relative loudness).
Launcher: launcher/src/main/resources/net/veloclient/launcher/sounds/<name>.wav (Java Sound plays no Ogg).
Music: assets/velo-client/sounds/music/title_ambient.ogg (stereo, loops seamlessly).

Levels: every UI sound is measured against vanilla's click as the game plays it (peak ~0.24 after its 0.25 volume) -
taps sit a little under it, the menu swell and the launch hit a little over, nothing jumps out. The title track sits
at vanilla menu music's loudness (RMS ~0.06).
"""
import importlib.util
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("emote_sounds", HERE.parent / "cosmetics" / "emotes" / "make_sounds.py")
E = importlib.util.module_from_spec(spec)
spec.loader.exec_module(E)

try:
    # the shared one-pole filter runs per sample in Python - fine for short sounds, minutes for the title track
    from scipy.signal import lfilter

    _slow_lowpass = E.lowpass

    def _fast_lowpass(x, cutoff):
        if np.ndim(cutoff) == 0:
            a = 1 - np.exp(-2 * np.pi * float(cutoff) / E.SR)
            return lfilter([a], [1, -(1 - a)], x)
        return _slow_lowpass(x, cutoff)

    E.lowpass = _fast_lowpass
except ImportError:
    pass

ROOT = HERE.parents[1]
OUT = E.ASSETS / "sounds/ui"
MUSIC_OUT = E.ASSETS / "sounds/music"
LAUNCHER = ROOT / "launcher/src/main/resources/net/veloclient/launcher/sounds"
SR = E.SR
RNG = np.random.default_rng(7)
t_, env, sine, noise = E.t_, E.env, E.sine, E.noise
lp, hp, bandpass, reverb, mixdown, at = E.lp, E.hp, E.bandpass, E.reverb, E.mixdown, E.at

# Playback volume the game uses for every UI sound (VeloUiSounds.VOLUME) - files are levelled for it.
GAME_VOLUME = 0.3


def note(name):
    """'A4' -> Hz."""
    names = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}
    n = names[name[0]] + (1 if "#" in name else -1 if name[1] == "b" else 0)
    octave = int(name[-1])
    return 440.0 * 2 ** ((n + (octave - 4) * 12) / 12)


def soft_tone(f, d, decay=0.18, bright=0.25):
    """A mellow marimba/kalimba-ish tone: sine body, a little 2nd/3rd harmonic that dies fast, rounded attack."""
    tt = t_(d)
    body = np.sin(2 * np.pi * f * tt) * np.exp(-tt / decay)
    over = (np.sin(2 * np.pi * f * 2.0 * tt) * 0.35 + np.sin(2 * np.pi * f * 3.01 * tt) * 0.12) * np.exp(-tt / (decay * 0.28)) * bright * 4
    att = np.clip(tt / 0.003, 0, 1)
    return (body + over) * att


def glass_pluck(f, d=0.9, decay=0.35):
    """A soft glassy pluck: FM bell, gentle, a touch of shimmer."""
    tt = t_(d)
    mod = np.sin(2 * np.pi * f * 3.5 * tt) * 1.2 * np.exp(-tt / 0.08)
    x = np.sin(2 * np.pi * f * tt + mod) * np.exp(-tt / decay)
    return x * np.clip(tt / 0.004, 0, 1)


def tick(d=0.035, f=2400, level=1.0):
    """The tactile part of a click: a tiny band-limited transient plus a short body."""
    tr = bandpass(noise(d), f, 2.5) * env(d, 0.0003, 0.006) * 2.2
    body = sine(f * 0.42, d) * env(d, 0.0005, 0.012) * 0.6
    thock = sine(170, d) * env(d, 0.001, 0.018) * 0.5
    return mixdown(tr, body, thock) * level


def whoosh(d=0.35, f0=300, f1=2600, up=True, q=1.4):
    """An airy filtered-noise sweep (rising or falling), smooth in and out."""
    n = int(d * SR)
    x = noise(d)
    out = np.zeros(n)
    # sweep a bandpass in short overlapping blocks
    block = 512
    win = np.hanning(block * 2)
    for i in range(0, n - block, block):
        p = i / max(1, n - block)
        p = p if up else 1 - p
        f = f0 * (f1 / f0) ** p
        seg = x[i:i + block * 2]
        if len(seg) < block * 2:
            break
        out[i:i + block * 2] += bandpass(seg, f, q) * win
    shape = np.sin(np.pi * np.clip(t_(d) / d, 0, 1)) ** 1.6
    return out * shape


def pad_chord(freqs, d, attack=0.25, release=0.5, cutoff=1800, detune=0.004):
    """Warm detuned-saw pad, low-passed."""
    tt = t_(d)
    x = np.zeros(len(tt))
    for f in freqs:
        for k in (-1, 0, 1):
            ph = RNG.random() * 2 * np.pi
            ff = f * (1 + k * detune)
            # band-limited-ish saw from a few harmonics
            for h in range(1, 8):
                x += np.sin(2 * np.pi * ff * h * tt + ph * h) / h * (0.9 ** h)
    x = lp(x, cutoff, 2)
    e = np.clip(tt / attack, 0, 1) ** 2 * np.clip((d - tt) / release, 0, 1)
    return x * e / (len(freqs) * 3)


def level(x, peak):
    """Scale so the loudest sample hits peak (already accounting for the game's playback volume)."""
    m = np.max(np.abs(x)) + 1e-9
    return x / m * min(0.98, peak / GAME_VOLUME)


def fade_tail(x, ms=8):
    n = min(len(x), int(ms / 1000 * SR))
    x = x.copy()
    x[-n:] *= np.linspace(1, 0, n)
    return x


# ---- the sounds (peak values are what you hear in game, vanilla click = 0.24) ----

def click():
    return level(tick(0.05, 2600), 0.17)


def hover():
    # barely there - only used on the guide's big cards
    x = mixdown(soft_tone(note("E6"), 0.06, decay=0.02, bright=0.1) * 0.5, bandpass(noise(0.03), 3500, 3) * env(0.03, 0.0005, 0.005) * 0.4)
    return level(x, 0.05)


def toggle_on():
    a = tick(0.05, 2800)
    b = at(soft_tone(note("E5"), 0.28, 0.07), 0.012)
    c = at(soft_tone(note("B5"), 0.32, 0.09), 0.055)
    x = mixdown(a * 0.8, b * 0.55, c * 0.6)
    return level(reverb(x, 0.6, 0.12, 5000), 0.2)


def toggle_off():
    a = tick(0.05, 2000)
    b = at(soft_tone(note("B4"), 0.26, 0.07, 0.15), 0.012)
    c = at(soft_tone(note("E4"), 0.3, 0.08, 0.1), 0.05)
    x = mixdown(a * 0.8, b * 0.5, c * 0.55)
    return level(reverb(x, 0.6, 0.1, 4000), 0.17)


def slider():
    return level(mixdown(tick(0.025, 3200) * 0.6, soft_tone(note("A6"), 0.03, 0.012, 0.05) * 0.2), 0.06)


def tab():
    """A modern tab switch: a crisp, tiny tick and a short glassy blip that rises a fifth - no whoosh."""
    t = tick(0.03, 3400) * 0.7
    d = 0.09
    tt = t_(d)
    f = note("E6") * (1 + 0.5 * np.clip(tt / 0.025, 0, 1))  # E6 -> B6 glide in 25 ms
    blip = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / 0.022) * np.clip(tt / 0.002, 0, 1)
    x = mixdown(t, at(lp(blip, 7000) * 0.5, 0.004))
    return fade_tail(level(reverb(x, 0.35, 0.08, 6000)[:int(0.16 * SR)], 0.11), 20)


def menu_open():
    d = 1.1
    w = whoosh(0.32, 260, 2600, True, 1.2) * 0.9
    chord = pad_chord([note("F3"), note("A3"), note("C4"), note("E4"), note("G4")], d, 0.12, 0.7, 1500) * 2.4
    pl = mixdown(at(glass_pluck(note("E5"), 0.8, 0.3) * 0.45, 0.2), at(glass_pluck(note("A5"), 0.7, 0.28) * 0.3, 0.27))
    x = mixdown(w, at(chord, 0.06), pl)
    x = reverb(x, 1.4, 0.28, 4500)
    return fade_tail(level(x[:int(1.5 * SR)], 0.24), 60)


def menu_close():
    w = whoosh(0.24, 2400, 300, True, 1.2)
    pl = mixdown(at(glass_pluck(note("C5"), 0.6, 0.2) * 0.35, 0.05), at(glass_pluck(note("F4"), 0.6, 0.22) * 0.35, 0.1))
    x = reverb(mixdown(w * 0.8, pl), 1.0, 0.2, 4000)
    return fade_tail(level(x[:int(0.9 * SR)], 0.16), 40)


def _pause_blip(f0, f1, level_peak):
    """The Escape menu: one small, direct glassy blip gliding f0 -> f1 - quieter and shorter than the Velo menu."""
    d = 0.14
    tt = t_(d)
    f = f0 + (f1 - f0) * np.clip(tt / 0.03, 0, 1)
    blip = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / 0.035) * np.clip(tt / 0.0015, 0, 1)
    x = mixdown(tick(0.018, 2200) * 0.35, lp(blip, 5200) * 0.8)
    return fade_tail(level(reverb(x, 0.18, 0.04, 6000)[:int(0.2 * SR)], level_peak), 18)


def pause_open():
    return _pause_blip(note("D5"), note("A5"), 0.1)


def pause_close():
    return _pause_blip(note("A5"), note("D5"), 0.085)


def guide_next():
    w = lp(whoosh(0.22, 450, 2600, True, 1.6), 4200)
    pl = at(glass_pluck(note("A5"), 0.5, 0.18) * 0.4, 0.12)
    return fade_tail(level(reverb(mixdown(w * 0.8, pl), 0.9, 0.18, 5000)[:int(0.8 * SR)], 0.15), 40)


def guide_back():
    w = lp(whoosh(0.2, 2600, 450, True, 1.6), 4200)
    pl = at(glass_pluck(note("E5"), 0.45, 0.15) * 0.3, 0.08)
    return fade_tail(level(reverb(mixdown(w * 0.8, pl), 0.9, 0.16, 4500)[:int(0.7 * SR)], 0.13), 40)


def guide_done():
    notes = ["C5", "E5", "G5", "B5", "D6"]
    x = np.zeros(int(2.2 * SR))
    for i, n in enumerate(notes):
        x = mixdown(x, at(glass_pluck(note(n), 1.6, 0.6) * (0.5 - i * 0.04), 0.07 * i))
    x = mixdown(x, pad_chord([note("C4"), note("G4"), note("E5")], 2.0, 0.3, 1.2, 1600) * 1.2)
    return fade_tail(level(reverb(x, 1.8, 0.3, 5500)[:int(2.6 * SR)], 0.22), 200)


def welcome():
    d = 3.0
    chord = pad_chord([note("D3"), note("A3"), note("C#4"), note("E4"), note("F#4")], d, 1.0, 1.4, 1300) * 2.0
    sub = sine(note("D2"), d) * env(d, 0.9, None, 0.6, 1.3) * 0.35
    shimmer = mixdown(*[at(glass_pluck(note(n), 1.4, 0.5) * 0.18, 0.9 + 0.16 * i) for i, n in enumerate(["F#5", "A5", "C#6"])])
    x = reverb(mixdown(chord, sub, shimmer), 2.0, 0.32, 5000)
    return fade_tail(level(x[:int(3.4 * SR)], 0.22), 300)


def game_loaded():
    """Plays once when the title screen first appears: a warm, moody swell that settles."""
    d = 3.2
    chord = pad_chord([note("A2"), note("E3"), note("B3"), note("C#4"), note("G#4")], d, 0.6, 1.8, 1100) * 2.2
    sub = sine(note("A1"), d) * env(d, 0.4, None, 0.8, 1.6) * 0.45
    air = lp(noise(d), 2500, 2) * env(d, 0.8, None, 0.3, 1.4) * 0.06
    pl = mixdown(at(glass_pluck(note("E5"), 1.6, 0.6) * 0.25, 0.55), at(glass_pluck(note("B5"), 1.6, 0.6) * 0.18, 0.75))
    x = reverb(mixdown(chord, sub, air, pl), 2.2, 0.32, 4500)
    return fade_tail(level(x[:int(3.8 * SR)], 0.24), 400)


def launcher_start():
    d = 2.2
    chord = pad_chord([note("F3"), note("C4"), note("E4"), note("A4")], d, 0.5, 1.2, 1500) * 2.0
    logo = mixdown(at(glass_pluck(note("C6"), 1.4, 0.5) * 0.35, 0.45), at(glass_pluck(note("G5"), 1.4, 0.5) * 0.3, 0.52))
    w = at(whoosh(0.5, 200, 1800, True, 1.0) * 0.5, 0.0)
    x = reverb(mixdown(chord, logo, w), 1.8, 0.3, 5000)
    return fade_tail(level(x[:int(2.6 * SR)], 0.22), 250)


def launch():
    """Play pressed: a soft deep thump, a rising swell, a gentle bloom on top."""
    thump = sine(110 * np.exp(-t_(0.5) * 9) + 42, 0.5) * env(0.5, 0.002, 0.28) * 1.1
    rise = whoosh(0.7, 200, 3000, True, 1.1) * 0.7
    bloom = at(pad_chord([note("A3"), note("E4"), note("G#4"), note("C#5")], 1.4, 0.05, 1.0, 2200) * 1.8, 0.62)
    sparkle = at(glass_pluck(note("E6"), 1.0, 0.35) * 0.25, 0.66)
    x = reverb(mixdown(thump, rise, bloom, sparkle), 1.6, 0.26, 5500)
    return fade_tail(level(x[:int(2.2 * SR)], 0.26), 200)


SOUNDS = {
    "click": click, "hover": hover, "toggle_on": toggle_on, "toggle_off": toggle_off, "slider": slider, "tab": tab,
    "menu_open": menu_open, "menu_close": menu_close, "pause_open": pause_open, "pause_close": pause_close, "guide_next": guide_next, "guide_back": guide_back,
    "guide_done": guide_done, "welcome": welcome, "game_loaded": game_loaded, "launcher_start": launcher_start, "launch": launch,
}
# what the launcher gets as WAV
LAUNCHER_SOUNDS = ["click", "toggle_on", "toggle_off", "tab", "launcher_start", "launch"]


# ---- the title track ----

def title_track():
    """~87 s cinematic but calm loop (66 BPM): string-like pads with slow swells, a soft choir, a round sub, deep
    drum hits that join on the second pass, rising air before each section and a bell melody on the last pass.
    Dmaj9 - Bm11 - Gmaj7#11 - A6sus, 2 bars each, three passes, seamless."""
    bpm = 66
    beat = 60 / bpm
    bar = beat * 4
    chords = [
        ["D3", "A3", "C#4", "E4", "F#4"],
        ["B2", "F#3", "A3", "D4", "E4"],
        ["G2", "D3", "F#3", "B3", "C#4"],
        ["A2", "E3", "F#3", "B3", "D4"],
    ]
    roots = ["D2", "B1", "G1", "A1"]
    arps = [
        ["F#5", "A5", "C#6", "E6", "C#6", "A5"],
        ["D5", "F#5", "A5", "E6", "A5", "F#5"],
        ["B4", "D5", "F#5", "C#6", "F#5", "D5"],
        ["C#5", "E5", "A5", "B5", "A5", "E5"],
    ]
    melody = [["F#5", "E5", "A5"], ["F#5", "D5", "E5"], ["D5", "C#5", "B4"], ["C#5", "E5", "A5"]]
    passes = 3
    seg = 2 * bar
    cycle = seg * 4
    total = cycle * passes
    tail = 7.0
    n = int((total + tail) * SR)
    L = np.zeros(n)
    R = np.zeros(n)

    def add(x, start, pan=0.0, gain=1.0):
        i = int(start * SR)
        m = min(len(x), n - i)
        if m <= 0:
            return
        L[i:i + m] += x[:m] * gain * np.sqrt(0.5 * (1 - pan))
        R[i:i + m] += x[:m] * gain * np.sqrt(0.5 * (1 + pan))

    def strings(freqs, d, swell=2.2):
        """Ensemble pad: many detuned saws, slow attack, gentle vibrato, warm low-pass."""
        tt = t_(d)
        x = np.zeros(len(tt))
        vib = 1 + 0.0035 * np.sin(2 * np.pi * 5.2 * tt)
        for f in freqs:
            for k in (-2, -1, 0, 1, 2):
                ph = RNG.random() * 2 * np.pi
                ff = f * (1 + k * 0.0032) * vib
                acc = np.cumsum(ff) / SR
                for h in range(1, 9):
                    x += np.sin(2 * np.pi * h * acc + ph * h) / h * (0.86 ** h)
        x = lp(x, 1500, 2)
        e = np.clip(tt / swell, 0, 1) ** 2 * np.clip((d - tt) / 2.0, 0, 1)
        return x * e / (len(freqs) * 5)

    def choir(freqs, d):
        """Soft 'ooh': sines with formant-ish overtones and slow vibrato."""
        tt = t_(d)
        x = np.zeros(len(tt))
        for f in freqs:
            vib = 1 + 0.006 * np.sin(2 * np.pi * (4.6 + RNG.random()) * tt + RNG.random() * 6)
            acc = np.cumsum(f * vib) / SR
            x += np.sin(2 * np.pi * acc) + 0.35 * np.sin(4 * np.pi * acc) + 0.12 * np.sin(6 * np.pi * acc)
        x = lp(x, 2200, 1)
        e = np.clip(tt / 2.5, 0, 1) ** 2 * np.clip((d - tt) / 2.2, 0, 1)
        return x * e / len(freqs)

    def drum(d=1.6):
        """A deep, soft cinematic hit (taiko-ish body, no click)."""
        tt = t_(d)
        body = np.sin(2 * np.pi * np.cumsum(75 * np.exp(-tt * 5) + 42) / SR) * np.exp(-tt * 3.2)
        thump = lp(noise(d), 260, 2) * np.exp(-tt * 9) * 2.5
        return (body + thump) * np.clip(tt / 0.004, 0, 1)

    def riser(d):
        """Air that swells into the next section."""
        tt = t_(d)
        x = lp(noise(d), 1200 + 3800 * (tt / d) ** 2, 2)
        return x * (tt / d) ** 2.4 * np.clip((d - tt) / 0.08, 0, 1)

    for p in range(passes):
        for c in range(4):
            t0 = p * cycle + c * seg
            fr = [note(f) for f in chords[c]]
            add(strings(fr, seg + 2.2), t0, -0.3, 0.6)
            add(strings(fr, seg + 2.2, 2.6), t0 + 0.04, 0.3, 0.6)
            if p >= 1:
                add(choir([f * 2 for f in fr[1:4]], seg + 2.0), t0, 0.0, 0.16 + 0.06 * p)
            bd = seg + 1.0
            bass = sine(note(roots[c]), bd) * env(bd, 0.3, None, seg - 0.6, 1.2) + sine(note(roots[c]) * 2, bd) * env(bd, 0.3, None, seg - 0.6, 1.2) * 0.2
            add(lp(bass, 380), t0, 0.0, 0.36)
            if p >= 1:
                # drums: a big one on the downbeat, a softer answer later in the second bar
                add(drum(), t0, 0.0, 0.22)
                add(drum(1.2) * 0.6, t0 + bar + beat * 2.5, 0.0, 0.16)
                if c == 3:
                    for k in range(3):
                        add(drum(0.9) * (0.35 + 0.15 * k), t0 + bar + beat * (3 + k * 0.33), 0.0, 0.12)
            if p >= 1:
                for k in range(16):
                    if (k % 8) in (3, 7) and RNG.random() < 0.6:
                        continue
                    nn = arps[c][k % len(arps[c])]
                    when = t0 + k * beat / 2 + (0.035 if k % 2 else 0)
                    tone = lp(soft_tone(note(nn), 1.4, 0.45, 0.12) * (0.75 + 0.25 * RNG.random()), 3800)
                    pan = -0.35 + 0.7 * ((k * 5) % 8) / 7
                    add(tone, when, pan, 0.11)
                    add(lp(tone, 2400), when + beat * 0.75, -pan, 0.05)
            if p == 2:
                # the bell melody: three long notes per chord
                for k, nn in enumerate(melody[c]):
                    add(glass_pluck(note(nn), 3.0, 1.1) * 0.55, t0 + k * beat * 2.6, 0.15 - 0.1 * k, 0.11)
                    add(glass_pluck(note(nn) * 2, 2.0, 0.6) * 0.2, t0 + k * beat * 2.6 + 0.01, -0.2, 0.05)
            if c == 3:
                add(riser(bar * 1.2), t0 + seg - bar * 1.2, 0.0, 0.05 + 0.02 * p)
    air = lp(noise(total + tail), 1600, 2) * 0.01
    L += air
    R += np.roll(air, 1234)
    L = reverb(L, 3.0, 0.34, 4200)
    R = reverb(R, 3.1, 0.34, 4200)
    k = int(total * SR)
    L[:n - k] += L[k:n]
    R[:n - k] += R[k:n]
    L, R = L[:k], R[:k]
    st = np.stack([L, R])
    rms = np.sqrt((st ** 2).mean())
    st *= 0.062 / (rms + 1e-9)
    pk = np.max(np.abs(st))
    if pk > 0.55:
        st = np.tanh(st / 0.55) * 0.55
    return st


def write_wav(x, path, channels=1):
    path.parent.mkdir(parents=True, exist_ok=True)
    x = np.clip(x, -1, 1)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(SR)
        data = x.T.reshape(-1) if channels == 2 else x
        w.writeframes((data * 32767).astype("<i2").tobytes())


def write_ogg_stereo(st, path, quality=5):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix=".wav") as tmp:
        write_wav(st, Path(tmp.name), 2)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-c:a", "libvorbis", "-q:a", str(quality), str(path)], check=True)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    names = args or list(SOUNDS)
    entries = {}
    for name in names:
        x = SOUNDS[name]()
        E.write_ogg(x.copy(), OUT / f"{name}.ogg")
        entries[f"ui.{name}"] = {"sounds": [{"name": f"velo-client:ui/{name}"}]}
        if name in LAUNCHER_SOUNDS:
            # the launcher plays these at full volume: bake the game's playback volume in
            write_wav(x * GAME_VOLUME, LAUNCHER / f"{name}.wav")
        print(f"{name:15s} {len(x) / SR:5.2f} s  peak in game {np.max(np.abs(x)) * GAME_VOLUME:.3f}")
    if "--music" in sys.argv or not args:
        st = title_track()
        write_ogg_stereo(st, MUSIC_OUT / "title_ambient.ogg")
        entries["music.title_ambient"] = {"sounds": [{"name": "velo-client:music/title_ambient", "stream": True}]}
        print(f"title_ambient   {st.shape[1] / SR:5.1f} s  rms {np.sqrt((st ** 2).mean()):.3f} peak {np.max(np.abs(st)):.3f}")
    E.update_sounds_json(entries)


if __name__ == "__main__":
    main()
