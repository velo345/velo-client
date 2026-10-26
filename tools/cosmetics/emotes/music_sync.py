#!/usr/bin/env python3
"""Puts the dance emotes on the beat of their music.

Music side: the track's beat grid (tempo from incompetech's catalogue, refined; phase from the onset envelope), the
downbeat (the beat of four with the strongest low end), and the loudest stretch of whole bars - that's cut out as a
seamless loop, a whole multiple of the dance's loop long.

Dance side: the clip's own beat (autocorrelation of how much the body moves), the clip time-stretched so a whole number
of its beats fits a whole number of music beats, rotated so its accents land on the beats, and its loop seam blended.
The game starts the music and the dance together (both at t = 0), so they stay locked for as long as it plays.
"""
import json
import subprocess
from pathlib import Path

import numpy as np

SR = 22050
HOP = 256


def decode(path, sr=SR):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", str(sr), "-f", "f32le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def onset_envelope(x):
    """Spectral flux (log magnitude, positive changes), one value per HOP samples."""
    n_fft = 1024
    frames = (len(x) - n_fft) // HOP
    win = np.hanning(n_fft)
    idx = np.arange(n_fft)[None, :] + HOP * np.arange(frames)[:, None]
    mag = np.abs(np.fft.rfft(x[idx] * win, axis=1))
    logm = np.log1p(100 * mag)
    flux = np.maximum(0, np.diff(logm, axis=0)).sum(axis=1)
    low = np.maximum(0, np.diff(logm[:, :24], axis=0)).sum(axis=1)  # below ~500 Hz: kicks
    flux = np.concatenate([[0], flux])
    low = np.concatenate([[0], low])
    flux -= np.convolve(flux, np.ones(32) / 32, mode="same")  # local mean off
    return np.maximum(flux, 0), low


def beat_grid(x, bpm_hint):
    """(bpm, first beat time, downbeat index 0..3) for the track."""
    env, low = onset_envelope(x)
    rate = SR / HOP
    best = None
    for bpm in np.linspace(bpm_hint * 0.985, bpm_hint * 1.015, 61):
        period = rate * 60 / bpm
        n = int((len(env) - period) / period)
        for phase in np.linspace(0, period, 48, endpoint=False):
            pos = (phase + period * np.arange(n)).astype(int)
            score = env[pos].sum() / n
            if best is None or score > best[0]:
                best = (score, bpm, phase)
    _, bpm, phase = best
    period = rate * 60 / bpm
    n = int((len(env) - phase) / period)
    pos = (phase + period * np.arange(n)).astype(int)
    strength = [low[pos[k::4]].mean() for k in range(4)]
    return bpm, phase / rate, int(np.argmax(strength))


def pick_segment(x, bpm, first, downbeat, beats, skip=0.12, until=0.85):
    """Start time of the loudest run of `beats` beats starting on a downbeat (not in the intro / outro)."""
    beat = 60 / bpm
    start0 = first + downbeat * beat
    seg = beats * beat
    rms = np.sqrt(np.convolve(x ** 2, np.ones(SR // 2) / (SR // 2), mode="same"))
    best = None
    k = 0
    while True:
        s = start0 + k * 4 * beat
        if s + seg > len(x) / SR * until:
            break
        if s >= len(x) / SR * skip:
            a, b = int(s * SR), int((s + seg) * SR)
            score = rms[a:b].mean() - 0.5 * rms[a:b].std()
            if best is None or score > best[0]:
                best = (score, s)
        k += 1
    return best[1] if best else start0


def cut(x, start, length, loop=True, fade_out=0.0, sr=SR):
    a = int(round(start * sr))
    n = int(round(length * sr))
    seg = x[a:a + n].copy()
    if len(seg) < n:
        seg = np.concatenate([seg, np.zeros(n - len(seg))])
    if loop:
        # equal-power blend of the tail into what precedes the start, so the wrap is seamless
        m = int(0.03 * sr)
        if a - m >= 0:
            pre = x[a - m:a]
            w = np.linspace(0, 1, m)
            seg[-m:] = seg[-m:] * np.cos(w * np.pi / 2) + pre * np.sin(w * np.pi / 2)
    else:
        m = int(0.01 * sr)
        seg[:m] *= np.linspace(0, 1, m)
    if fade_out > 0:
        m = int(fade_out * sr)
        seg[-m:] *= np.linspace(1, 0, m) ** 1.5
    return seg


# ---------------------------------------------------------------- the dance side

def motion(frames, fps):
    """How much the body moves per frame (angles + pivots)."""
    a = np.array(frames, dtype=float)  # (n, 6, 6)
    d = np.diff(a, axis=0)
    d[..., 3:] = (d[..., 3:] + np.pi) % (2 * np.pi) - np.pi
    speed = np.abs(d[..., 3:]).sum(axis=(1, 2)) + np.abs(d[..., :3]).sum(axis=(1, 2)) / 8
    return np.concatenate([[speed[0]], speed]) * fps


def accents(frames, fps):
    """Where moves 'hit': the body stops or turns sharply (deceleration peaks)."""
    s = motion(frames, fps)
    s = np.convolve(s, np.ones(3) / 3, mode="same")
    return np.maximum(0, -np.gradient(s))


def clip_beat(frames, fps, lo=0.28, hi=1.3):
    """The clip's own beat period in seconds (autocorrelation of its motion)."""
    s = motion(frames, fps)
    s = s - s.mean()
    ac = np.correlate(s, s, mode="full")[len(s) - 1:]
    ac /= ac[0] if ac[0] else 1
    lags = np.arange(len(ac)) / fps
    ok = (lags >= lo) & (lags <= hi)
    if not ok.any():
        return None
    k = np.argmax(np.where(ok, ac, -1))
    return lags[k]


def resample(frames, n_out):
    """Frames re-timed to n_out (angle-aware linear interpolation)."""
    a = np.array(frames, dtype=float)
    n = len(a)
    out = []
    for i in range(n_out):
        f = i * (n - 1) / max(1, n_out - 1)
        k = min(n - 2, int(f))
        w = f - k
        lo, hi = a[k], a[k + 1]
        d = hi - lo
        d[:, 3:] = (d[:, 3:] + np.pi) % (2 * np.pi) - np.pi
        out.append(lo + d * w)
    return [np.round(o, 3).tolist() for o in out]


def loop_seam(frames, fps, blend=0.35):
    """Blends the end of a loop into its start so the wrap doesn't jump."""
    a = np.array(frames, dtype=float)
    m = min(len(a) // 3, int(blend * fps))
    for i in range(m):
        w = ((i + 1) / m) ** 2 * (3 - 2 * ((i + 1) / m)) if m else 1
        k = len(a) - m + i
        d = a[0] - a[k]
        d[:, 3:] = (d[:, 3:] + np.pi) % (2 * np.pi) - np.pi
        a[k] = a[k] + d * w
    return np.round(a, 3).tolist()


def sync_loop(frames, fps, music_beat, beats=None):
    """Re-times a dance loop to a whole number of music beats and rotates it so its accents fall on beats.
    Returns (frames, beats in the loop, time map: original clip seconds -> seconds in the new loop)."""
    duration = (len(frames) - 1) / fps
    own = clip_beat(frames, fps) or music_beat
    # the musical ratio between the clip's beat and the music's nearest to "no change"
    ratios = [r for r in (0.5, 1.0, 2.0) if 0.65 <= (own * r) / music_beat <= 1.5] or [1.0]
    ratio = min(ratios, key=lambda r: abs(np.log(own * r / music_beat)))
    if beats is None:
        clip_beats = duration / (own * ratio)
        beats = max(4, int(round(clip_beats / 2)) * 2)
    loop = beats * music_beat
    n_out = int(round(loop * fps)) + 1
    re = resample(frames, n_out)
    # rotate: put the strongest accent phase on the beat grid
    acc = accents(re, fps)
    per = music_beat * fps
    best = max(range(int(per)), key=lambda off: acc[(off + per * np.arange(beats)).astype(int) % (n_out - 1)].sum())
    body = re[:-1]
    body = body[best:] + body[:best]
    body.append(body[0])
    factor = loop / duration

    def remap(t):
        return (t * factor - best / fps) % loop
    return loop_seam(body, fps), beats, remap


def catalogue(title):
    data = json.loads((Path(__file__).resolve().parent / "work/music/pieces.json").read_text())
    for p in data:
        if p["filename"] == title + ".mp3" or p["title"] == title:
            return p
    raise KeyError(title)
