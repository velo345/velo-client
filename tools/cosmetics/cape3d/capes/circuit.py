"""Circuit's layout: the very same traces and chips as the original Circuit cape (make_capes.py build_circuit),
converted to cape units and handed to the shader as uniform arrays, so the new one is that design, sharp."""
import math
import sys
from pathlib import Path

W, H = 160, 256      # the old art's pixel size (10 x 16 cape pixels at 16 px)
MAX_SEGS = 400
MAX_TRACES = 32
MAX_CHIPS = 4


def _layout():
	"""The original generator itself, so the layout can never drift from the old cape."""
	sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
	import make_capes
	_, traces, _, chips = make_capes.build_circuit()
	return traces, chips


def _unit(px, py):
	return px / W * 10 - 5, 8 - py / H * 16


def uniforms():
	traces, chips = _layout()
	segs, info, totals, ends = [], [], [], []
	for k, t in enumerate(traces[:MAX_TRACES]):
		# Merge straight runs into one segment each: only the corners stay.
		pts = [_unit(*p) for p in t]
		merged = [pts[0]]
		for i in range(1, len(pts) - 1):
			d1 = (pts[i][0] - merged[-1][0], pts[i][1] - merged[-1][1])
			d2 = (pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1])
			if abs(d1[0] * d2[1] - d1[1] * d2[0]) > 1e-9:
				merged.append(pts[i])
		merged.append(pts[-1])
		pts = merged
		along = 0.0
		for a, b in zip(pts, pts[1:]):
			if len(segs) >= MAX_SEGS:
				break
			length = math.dist(a, b)
			if length < 1e-6:
				continue
			segs.append((*a, *b))
			info.append((k, along, length, 0.0))
			along += length
		totals.append(along)
		ends.append((*pts[0], *pts[-1]))
	n_segs = len(segs)
	while len(segs) < MAX_SEGS:
		segs.append((0, 0, 0, 0))
		info.append((-1, 0, 0, 0))
	n_traces = len(totals)
	while len(totals) < MAX_TRACES:
		totals.append(0.0)
		ends.append((0, 0, 0, 0))
	rects = []
	for (cx, cy, cw, ch) in chips:
		x0, y1 = _unit(cx, cy)
		x1, y0 = _unit(cx + cw, cy + ch)
		rects.append(((x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2))
	assert n_segs < MAX_SEGS, n_segs
	return {
		"uSegs": segs, "uSegInfo": info, "uSegCount": n_segs,
		"uTraceLen": totals, "uTraceEnds": ends, "uTraceCount": n_traces,
		"uChips": rects,
	}
