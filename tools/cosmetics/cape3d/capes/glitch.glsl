// Glitch - the sharp Velo logo on a dead-black screen that keeps breaking: mostly calm with a faint chromatic fringe,
// then in bursts the picture tears - slices jump sideways, red, green and blue split apart, corrupt blocks flash.

const vec3 GLOW = vec3(0.5, 1.6, 2.4);
const vec3 HOT = vec3(2.4, 2.4, 2.6);
const vec3 FACE0 = vec3(0.004, 0.004, 0.008);
const vec3 FACE1 = vec3(0.03, 0.03, 0.05);
const vec3 AMB0 = vec3(0.004, 0.004, 0.006);
const vec3 AMB1 = vec3(0.04, 0.04, 0.05);
const vec3 FABRIC = vec3(0.007, 0.007, 0.01);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.6;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define CUSTOM_COLOR

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = vec3(0.004, 0.004, 0.007) + halo * GLOW * 0.06;
	// Faint scanlines and a rolling bright band.
	c *= 0.85 + 0.15 * sin(P.y * 70.0);
	c += exp(-pow2(P.y - (9.0 - 18.0 * fract(uPhase * 2.0))) * 0.8) * vec3(0.01, 0.015, 0.02);
	return c;
}

// How hard it's glitching now: three bursts per loop, each flickering on and off frame by frame.
float burst() {
	float f = floor(uPhase * 40.0);
	float b = step(0.18, uPhase) * step(uPhase, 0.27) + step(0.52, uPhase) * step(uPhase, 0.56) + step(0.78, uPhase) * step(uPhase, 0.9);
	return b * step(0.3, hash11(f * 1.7));
}

vec3 capeColor(vec2 uv, int face) {
	if (face == 1) return kitInside(uv);
	if (face != 0) {
		vec3 e = kitEdge(uv, face == 2 || face == 3);
		return e * (1.0 + burst() * hash11(floor(uPhase * 40.0) + uv.x * 10.0 + uv.y * 16.0));
	}
	float b = burst();
	float frame = floor(uPhase * 40.0);
	vec2 u = uv;
	// Slices: horizontal bands that jump sideways during a burst.
	float band = floor(uv.y * 34.0);
	float hb = hash21(vec2(band, frame));
	u.x += b * (hb > 0.6 ? (hash21(vec2(band, frame + 7.0)) - 0.5) * 0.35 : 0.0);
	// Channel split: wide in a burst, a hairline otherwise.
	float split = 0.0025 + b * (0.02 + 0.03 * hash11(frame));
	vec3 col;
	if (b > 0.0) {
		col = vec3(outside(u + vec2(split, 0.0)).r, outside(u).g, outside(u - vec2(split, 0.0)).b);
	} else {
		vec3 mid = outside(u);
		col = mid;
		// The calm fringe: just the logo's glow shifted a touch towards red and blue.
		col.r = max(col.r, outside(u + vec2(split, 0.0)).r * 0.6);
	}
	// Corrupt blocks: a few rectangles inverted or flooded with colour.
	vec2 bc = floor(uv * vec2(8.0, 22.0));
	float hbk = hash21(bc + frame * 3.1);
	if (b > 0.0 && hbk > 0.95) {
		col = hbk > 0.988 ? vec3(0.85) - col * 0.8 : col * vec3(0.3, 1.6, 2.0) + vec3(0.0, 0.02, 0.04);
	}
	// Static noise in a burst.
	col += b * spow(hash21(uv * 900.0 + frame), 6.0) * 0.12;
	return col;
}
