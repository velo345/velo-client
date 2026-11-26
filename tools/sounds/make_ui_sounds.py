"""
Velo's interface sounds (2026-11-25): soft, rounded, quiet - console-menu style. Every sound is synthesised here, so the
set can be tuned and rebuilt in one go:

  python3 tools/sounds/make_ui_sounds.py

Writes WAV for the launcher (launcher/src/main/resources/net/veloclient/launcher/sounds/) and mono OGG for the game
(src/client/resources/assets/velo-client/sounds/ui/). Levels are low on purpose: a hover is barely there, a click is
a soft tap, only "launch" and "welcome" are a little bigger.
"""
import os
import subprocess
import wave

import numpy as np
from scipy.signal import butter, lfilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LAUNCHER = os.path.join(ROOT, "launcher/src/main/resources/net/veloclient/launcher/sounds")
GAME = os.path.join(ROOT, "src/client/resources/assets/velo-client/sounds/ui")
SR = 44100
RNG = np.random.default_rng(7)


def t(seconds):
    return np.arange(int(SR * seconds)) / SR


# 2026-11-26 redesign (owner: "klingt wie Holz", "billig wie ein Mobile Game"). What made the old set sound like wood:
# a 2.76x partial (the overtone of a xylophone bar), 1-4 ms attacks and 15-30 ms decays - a struck bar. Now: pure sine
# bodies (detuned pairs for warmth), attacks of 5-15 ms so nothing knocks, soft pitch-drop "pops" for presses, a gentle
# low-pass over everything and a darker, smoother room. Small sounds stay short and quiet, the big moments are pads.

def env(n, attack, decay_tau, release=0.03):
    """Raised-cosine attack, exponential decay, a soft fade at the very end."""
    x = np.arange(n) / SR
    a = np.clip(x / max(attack, 1e-4), 0, 1)
    a = 0.5 - 0.5 * np.cos(np.pi * a)
    d = np.exp(-x / decay_tau)
    r = np.clip((n / SR - x) / release, 0, 1)
    r = 0.5 - 0.5 * np.cos(np.pi * r)
    return a * d * r


def lowpass(sig, cutoff, order=2):
    b, a = butter(order, cutoff / (SR / 2), "low")
    return lfilter(b, a, sig)


def highpass(sig, cutoff, order=2):
    b, a = butter(order, cutoff / (SR / 2), "high")
    return lfilter(b, a, sig)


def tone(freq, seconds, attack=0.008, tau=0.12, warmth=0.12, cents=4.0, drop=0.0, drop_time=0.03):
    """A clean, round tone: two sines a few cents apart (a slow shimmer, no edge) plus a faint octave.
    {@code drop}: the pitch starts that fraction higher and glides down - the soft 'pop' of a press."""
    x = t(seconds)
    glide = 1 + drop * np.exp(-x / max(drop_time, 1e-4))
    out = np.zeros_like(x)
    for c, g in ((-cents / 2, 0.5), (cents / 2, 0.5)):
        f = freq * 2 ** (c / 1200) * glide
        phase = 2 * np.pi * np.cumsum(f) / SR
        out += g * np.sin(phase)
        out += g * warmth * np.sin(2 * phase) * np.exp(-x / (tau * 0.5))
    return out * env(len(x), attack, tau)


def air(seconds, lo=500, hi=3000, attack=0.06, tau=0.08):
    """Soft filtered air - the swell under page changes and the big moments. Band-passed, never hissy."""
    n = int(SR * seconds)
    w = RNG.standard_normal(n)
    w = lowpass(highpass(w, lo, 2), hi, 2)
    w /= np.max(np.abs(w)) + 1e-9
    return w * env(n, attack, tau)


def pad(notes, seconds, attack=0.45, tau=1.2, cents=7.0):
    """A warm chord swell: detuned sine pairs per note, slow attack, long fade."""
    x = t(seconds)
    out = np.zeros_like(x)
    for i, (n, g) in enumerate(notes):
        out += g * tone(hz(n), seconds, attack=attack + 0.04 * i, tau=tau, warmth=0.18, cents=cents)
    return out


def room(sig, size=0.35, mix=0.25, predelay=0.012, damp=3200):
    """A smooth, dark room: diffuse noise tail (no flutter), low-passed so the reflections never sparkle."""
    n_tail = int(SR * size)
    x = np.arange(n_tail) / SR
    ir = RNG.standard_normal(n_tail) * np.exp(-x / (size / 5.0))
    ir = lowpass(ir, damp, 2)
    ir[: int(SR * 0.004)] *= np.linspace(0, 1, int(SR * 0.004))
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    pre = np.zeros(int(SR * predelay))
    ir = np.concatenate([pre, ir])
    total = len(sig) + len(ir)
    wet = np.convolve(sig, ir)[:total]
    dry = np.concatenate([sig, np.zeros(total - len(sig))])
    wet = np.concatenate([wet, np.zeros(total - len(wet))])
    return (1 - mix) * dry + mix * wet * 0.8


def seq(parts):
    """[(start_seconds, signal), ...] mixed into one."""
    end = max(int(s * SR) + len(sig) for s, sig in parts)
    out = np.zeros(end)
    for s, sig in parts:
        i = int(s * SR)
        out[i:i + len(sig)] += sig
    return out


def finish(sig, peak_db, cutoff=7000):
    """Low-pass (nothing harsh up top), trim silence at the end, then the level."""
    sig = lowpass(sig - np.mean(sig), cutoff, 2)
    a = np.abs(sig)
    keep = np.nonzero(a > a.max() * 10 ** (-60 / 20))[0]
    if len(keep):
        sig = sig[: keep[-1] + 1]
    fade = min(len(sig), int(SR * 0.02))
    sig[-fade:] *= np.linspace(1, 0, fade)
    p = np.max(np.abs(sig)) + 1e-9
    return sig / p * (10 ** (peak_db / 20))


NOTE = {n: 440 * 2 ** ((i - 9) / 12) for i, n in enumerate(["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"])}


def hz(name):
    return NOTE[name[:-1]] * 2 ** (int(name[-1]) - 4)


def tick(seconds=0.06, center=2600, width=0.5, attack=0.0025, tau=0.009):
    """A muted tactile tick: a short band of noise around {@code center}, rounded on both ends - felt more than heard."""
    n = int(SR * seconds)
    w = RNG.standard_normal(n)
    w = lowpass(highpass(w, center * (1 - width), 2), center * (1 + width), 2)
    w /= np.max(np.abs(w)) + 1e-9
    return w * env(n, attack, tau, release=0.01)


def sweep(seconds, lo, hi, rising=True, attack=0.06, tau=0.14):
    """Air whose low-pass opens (rising) or closes (falling) over the sound - a panel moving in or out."""
    n = int(SR * seconds)
    w = highpass(RNG.standard_normal(n), lo * 0.6, 2)
    out = np.zeros(n)
    blk = 256
    zi = None
    for i in range(0, n, blk):
        k = i / n
        k = k ** 0.7 if rising else 1 - k ** 0.9
        cut = lo + (hi - lo) * k
        b, a = butter(2, max(80, cut) / (SR / 2), "low")
        if zi is None:
            zi = np.zeros(max(len(a), len(b)) - 1)
        seg, zi = lfilter(b, a, w[i:i + blk], zi=zi)
        out[i:i + blk] = seg
    out /= np.max(np.abs(out)) + 1e-9
    return out * env(n, attack, tau)


def bloom(seconds=0.4, cutoff=180, attack=0.02, tau=0.11):
    """A soft low bloom (low-passed noise, no pitch) - gives a panel some weight."""
    n = int(SR * seconds)
    w = lowpass(lowpass(RNG.standard_normal(n), cutoff, 2), cutoff, 2)
    w /= np.max(np.abs(w)) + 1e-9
    return w * env(n, attack, tau)


def press(center, tau=0.008, thump=0.6, attack=0.0012):
    """One soft tactile press with no pitch: a burst of band-limited noise around {@code center} plus a low thump of
    low-passed noise (also unpitched) - a single short event."""
    n = int(SR * max(0.05, tau * 8))
    air_part = RNG.standard_normal(n)
    air_part = lowpass(highpass(air_part, center * 0.45, 2), center * 1.7, 2)
    air_part /= np.max(np.abs(air_part)) + 1e-9
    out = air_part * env(n, attack, tau, release=0.008)
    if thump > 0:
        low = lowpass(RNG.standard_normal(n), 320, 2)
        low /= np.max(np.abs(low)) + 1e-9
        out = out + thump * low * env(n, attack * 2, tau * 1.6, release=0.008)
    return out


def tap(freq, tau=0.02, attack=0.0025):
    """One short, soft tap: a single pure tone, rounded in over a few ms and damped out fast - no second layer."""
    return tone(freq, max(0.05, tau * 6), attack=attack, tau=tau, warmth=0.0, cents=0)


def body(freq, seconds=0.12, attack=0.004, tau=0.03):
    """A soft low body under a press (a pure sine, no pitch movement)."""
    return tone(freq, seconds, attack=attack, tau=tau, warmth=0.0, cents=0)


def whoosh(seconds, lo=300, hi=2400, attack=0.08, tau=0.09, sweep=True):
    """Moving air: noise through a low-pass that opens and closes - a page sliding, not a chime."""
    n = int(SR * seconds)
    w = RNG.standard_normal(n)
    w = highpass(w, lo, 2)
    if sweep:
        # filter the noise in short blocks with a cutoff that rises then falls (cheap time-varying low-pass)
        out = np.zeros(n)
        blk = 256
        zi = None
        for i in range(0, n, blk):
            k = i / n
            cut = lo + (hi - lo) * np.sin(np.pi * min(1.0, k * 1.15)) ** 1.5
            b, a = butter(2, max(80, cut) / (SR / 2), "low")
            if zi is None:
                zi = np.zeros(max(len(a), len(b)) - 1)
            seg, zi = lfilter(b, a, w[i:i + blk], zi=zi)
            out[i:i + blk] = seg
        w = out
    else:
        w = lowpass(w, hi, 2)
    w /= np.max(np.abs(w)) + 1e-9
    return w * env(n, attack, tau)


def rise(note, seconds, attack=0.03, tau=0.2, semis=1.5, glide=0.07):
    """A soft tone that starts {@code semis} below its note and glides up into it."""
    x = t(seconds)
    f = hz(note) * 2 ** (-semis / 12 * np.exp(-x / glide))
    out = np.zeros_like(x)
    for c in (-2.5, 2.5):
        phase = 2 * np.pi * np.cumsum(f * 2 ** (c / 1200)) / SR
        out += 0.5 * np.sin(phase) + 0.06 * np.sin(2 * phase)
    return out * env(len(x), attack, tau)


# 2026-11-26 second pass (owner: "kein 8-bit, kein weirdes Mobile Game, wirklich clean"): no melodies, no note pairs,
# no arpeggios. Presses are muted tactile ticks over a soft low body; pages and menus are moving air; feedback is one
# soft, warm tone (a fifth sounding together, never a jingle); only the start is a slow, cinematic swell.
SOUNDS = {
    # presses, third pass (owner 2026-11-26: the pure-tone taps were "zu melodisch", and random pitch on a tone sounds like
    # random notes): no tone at all - one short burst of filtered air with a soft low thump, like a quiet tactile key.
    # The takes differ in texture and brightness, never in a note.
    "hover": lambda: finish(press(3000, 0.0035, 0.0), -42, 7000),
    "slider": lambda: finish(press(2600, 0.003, 0.0), -42, 7000),
    "click": lambda: finish(room(press(1800, 0.008, 0.6), 0.1, 0.1), -24, 7000),
    "back": lambda: finish(room(press(1300, 0.009, 0.7), 0.1, 0.1), -25, 6000),
    "toggle_on": lambda: finish(room(press(2200, 0.008, 0.5), 0.1, 0.1), -24, 7000),
    "toggle_off": lambda: finish(room(press(1400, 0.008, 0.6), 0.1, 0.1), -25, 6000),
    "tab": lambda: finish(room(press(2000, 0.007, 0.45), 0.1, 0.1), -25, 7000),
    "page": lambda: finish(room(whoosh(0.32, 300, 2200, 0.1, 0.1), 0.3, 0.22), -27, 5000),
    # menus (owner 2026-11-26: R-Shift / Escape menu need nice open + close sounds, audible, still no notes):
    # air that opens up over a soft low bloom when a menu comes in, and closes down when it goes
    "menu_open": lambda: finish(room(seq([(0, sweep(0.38, 350, 3400, True, 0.07, 0.13)), (0.01, bloom(0.4, 200, 0.03, 0.11) * 0.8)]),
                                     0.3, 0.22), -17, 6000),
    "menu_close": lambda: finish(room(seq([(0, sweep(0.32, 350, 3000, False, 0.03, 0.11)), (0.0, bloom(0.34, 190, 0.02, 0.09) * 0.7)]),
                                      0.26, 0.2), -18, 5500),
    "pause_open": lambda: finish(room(seq([(0, sweep(0.42, 250, 2600, True, 0.08, 0.15)), (0.01, bloom(0.45, 170, 0.03, 0.13) * 0.9)]),
                                      0.32, 0.24), -17, 5000),
    "pause_close": lambda: finish(room(seq([(0, sweep(0.34, 250, 2400, False, 0.03, 0.12)), (0.0, bloom(0.36, 160, 0.02, 0.1) * 0.8)]),
                                       0.28, 0.22), -18, 4800),
    # the panel growing into the full menu / shrinking back, sliding away / coming back
    "expand": lambda: finish(room(seq([(0, sweep(0.3, 400, 3800, True, 0.05, 0.1)), (0.0, bloom(0.3, 220, 0.02, 0.08) * 0.5)]),
                                  0.22, 0.18), -19, 6500),
    "collapse": lambda: finish(room(seq([(0, sweep(0.26, 400, 3400, False, 0.02, 0.09)), (0.0, bloom(0.26, 200, 0.015, 0.07) * 0.5)]),
                                    0.2, 0.16), -20, 6000),
    # feedback: one warm tone (root + fifth together), soft attack, a calm tail
    # confirm = equip / saved / bought (owner 2026-11-26: the rising tone was a "ghost"): the same tap, a little fuller
    "confirm": lambda: finish(room(press(1600, 0.013, 1.0), 0.14, 0.14), -22, 6500),
    "error": lambda: finish(room(seq([(0, body(hz("D3"), 0.2, 0.008, 0.05)), (0.11, body(hz("D3"), 0.24, 0.008, 0.06) * 0.8)]),
                                 0.22, 0.2), -22, 3000),
    "notify": lambda: finish(room(seq([(0, tone(hz("A5"), 1.0, attack=0.015, tau=0.3, warmth=0.1, cents=5) * 0.55),
                                       (0, tone(hz("E5"), 1.0, attack=0.015, tau=0.26, warmth=0.1, cents=5) * 0.35)]), 0.6, 0.32), -25),
    "drag": lambda: finish(room(press(1100, 0.008, 0.6), 0.1, 0.1), -27, 6000),
    "drop": lambda: finish(room(press(1500, 0.011, 0.8), 0.1, 0.1), -25, 6000),
    # the guide: the same quiet language as the menus
    "guide_next": lambda: finish(room(whoosh(0.22, 300, 2400, 0.05, 0.07), 0.22, 0.2), -26, 5500),
    "guide_back": lambda: finish(room(whoosh(0.22, 300, 2000, 0.05, 0.07), 0.22, 0.2), -27, 5000),
    "guide_done": lambda: finish(room(seq([(0, whoosh(0.8, 200, 1800, 0.3, 0.25) * 0.4),
                                           (0.05, pad([("D3", 0.5), ("A3", 0.45), ("D4", 0.35), ("A4", 0.2)], 2.2, attack=0.35, tau=0.8))]),
                                      1.0, 0.38, damp=2600), -21, 5000),
    # big moments: cinematic swells - a slow pad, a breath of air, a warm sub; no notes played one after another
    "launch": lambda: finish(room(seq([(0, whoosh(1.0, 150, 2000, 0.45, 0.35) * 0.5),
                                       (0, tone(hz("D2"), 1.6, attack=0.18, tau=0.55, warmth=0.25, cents=2) * 0.45),
                                       (0.02, pad([("D3", 0.5), ("A3", 0.45), ("D4", 0.35), ("E4", 0.2), ("A4", 0.2)], 2.0, attack=0.25, tau=0.8))]),
                                  1.2, 0.38, damp=2400), -19, 5000),
    "launcher_start": lambda: finish(room(seq([(0, whoosh(1.8, 150, 1600, 0.9, 0.55) * 0.35),
                                               (0, tone(hz("D2"), 3.0, attack=0.7, tau=1.1, warmth=0.2, cents=2) * 0.35),
                                               (0.1, pad([("D3", 0.55), ("A3", 0.45), ("E4", 0.32), ("F#4", 0.26), ("A4", 0.18)], 3.4, attack=0.8, tau=1.4)),
                                               (0.9, tone(hz("A5"), 2.2, attack=0.5, tau=0.8, warmth=0.05, cents=8) * 0.07)]),
                                         1.8, 0.42, damp=2200), -20, 5000),
    "welcome": lambda: finish(room(seq([(0, whoosh(1.6, 150, 1600, 0.8, 0.5) * 0.35),
                                        (0, tone(hz("G2"), 2.8, attack=0.6, tau=1.0, warmth=0.2, cents=2) * 0.35),
                                        (0.1, pad([("G3", 0.5), ("D4", 0.45), ("A4", 0.32), ("B4", 0.25)], 3.2, attack=0.75, tau=1.3)),
                                        (0.8, tone(hz("D6"), 2.0, attack=0.5, tau=0.7, warmth=0.05, cents=8) * 0.06)]),
                                   1.7, 0.42, damp=2200), -20, 5000),
    "game_loaded": lambda: finish(room(seq([(0, whoosh(1.2, 150, 1600, 0.5, 0.4) * 0.3),
                                            (0.05, pad([("E3", 0.5), ("B3", 0.45), ("F#4", 0.3), ("G#4", 0.2)], 2.6, attack=0.5, tau=1.0))]),
                                       1.3, 0.4, damp=2400), -21, 5000),
}

# Small sounds you hear over and over get five takes each (owner 2026-11-26: "manche Menü-Clicks werden langweilig"):
# the same sound a little higher or lower with fresh noise, picked at random when played.
VARIANT_RATIOS = [1.0, 0.97, 1.03, 0.95, 1.05]   # small: the takes differ in texture, not in a note
VARIANTS = ["expand", "collapse", "confirm", "hover", "slider", "click", "back", "toggle_on", "toggle_off", "tab", "page", "drag", "drop", "guide_next", "guide_back"]


def repitch(sig, ratio):
    """Plays the sound {@code ratio} times faster (higher and a touch shorter) - plain resampling."""
    if ratio == 1.0:
        return sig
    n = int(len(sig) / ratio)
    return np.interp(np.arange(n) * ratio, np.arange(len(sig)), sig)


LAUNCHER_SET = ["hover", "click", "back", "toggle_on", "toggle_off", "slider", "tab", "page", "menu_open", "menu_close", "confirm", "error",
                "notify", "drag", "drop", "launch", "launcher_start"]


def write_wav(path, sig):
    data = (np.clip(sig, -1, 1) * 32767).astype(np.int16)
    stereo = np.repeat(data[:, None], 2, axis=1)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(stereo.tobytes())


def write_ogg(path, sig, tmp):
    data = (np.clip(sig, -1, 1) * 32767).astype(np.int16)
    with wave.open(tmp, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp, "-c:a", "libvorbis", "-q:a", "5", path], check=True)


def main():
    import json
    os.makedirs(LAUNCHER, exist_ok=True)
    os.makedirs(GAME, exist_ok=True)
    tmp = os.path.join(GAME, "_tmp.wav")
    for f in os.listdir(LAUNCHER):
        if f.endswith(".wav"):
            os.remove(os.path.join(LAUNCHER, f))
    for f in os.listdir(GAME):
        if f.endswith(".ogg"):
            os.remove(os.path.join(GAME, f))
    files = {}
    for name, make in SOUNDS.items():
        takes = VARIANT_RATIOS if name in VARIANTS else [1.0]
        files[name] = []
        for i, ratio in enumerate(takes):
            sig = repitch(make(), ratio)
            file = name if i == 0 else f"{name}_{i}"
            files[name].append(file)
            if name in LAUNCHER_SET:
                write_wav(os.path.join(LAUNCHER, file + ".wav"), sig)
            write_ogg(os.path.join(GAME, file + ".ogg"), sig, tmp)
        print(f"{name:15s} {len(takes)} take(s)")
    os.remove(tmp)
    # the game's sounds.json lists every take, Minecraft picks one at random
    sj = os.path.join(ROOT, "src/client/resources/assets/velo-client/sounds.json")
    data = json.load(open(sj))
    for name, takes in files.items():
        data["ui." + name] = {"sounds": [{"name": "velo-client:ui/" + f} for f in takes]}
    with open(sj, "w") as out:
        json.dump(data, out, indent=2)
        out.write("\n")


if __name__ == "__main__":
    main()
