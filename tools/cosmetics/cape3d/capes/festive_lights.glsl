// Festive Lights - three strings of glass bulbs in 3D across deep green velvet: a chase runs along them in red, gold,
// green, blue and white, the middle string swings, the bulbs light the velvet round them, snow drifts past.

const vec3 GLOW = vec3(2.2, 1.6, 0.6);
const vec3 HOT = vec3(2.6, 2.4, 2.0);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.005, 0.012, 0.007);
const vec3 AMB1 = vec3(0.04, 0.06, 0.045);
const vec3 FABRIC = vec3(0.006, 0.016, 0.01);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

const float SPACING = 1.05;

// String k's wire height at x, sagging between the cape's edges; the middle one swings once a loop.
float wireY(int k, float x) {
	float y0 = k == 0 ? 5.6 : k == 1 ? 1.2 : -3.4;
	float sag = (k == 1 ? 1.5 : 1.2) * (1.0 - pow2(x / 5.6));
	float swing = k == 1 ? 0.35 * sin(TAU * uPhase) * (1.0 - pow2(x / 5.6)) : 0.0;
	return y0 - sag + swing;
}

vec3 bulbColor(float idx) {
	int c = int(mod(idx, 5.0));
	return c == 0 ? vec3(2.4, 0.15, 0.1) : c == 1 ? vec3(2.4, 1.5, 0.3) : c == 2 ? vec3(0.2, 2.0, 0.4) : c == 3 ? vec3(0.3, 0.7, 2.6) : vec3(2.3, 2.2, 2.0);
}

// The chase: a bright wave running along each string, three laps of its five colours per loop.
float chase(int k, float idx) {
	float dir = k == 1 ? -1.0 : 1.0;
	float w = spow(0.5 + 0.5 * sin(TAU * uPhase * 3.0 * dir - idx * TAU / 5.0 + float(k)), 3.0);
	float twinkle = 0.85 + 0.15 * sin(TAU * uPhase * (4.0 + mod(idx, 3.0)) + idx * 2.0);
	return (0.18 + 0.82 * w) * twinkle;
}

vec3 bulbCentre(int k, float idx) {
	float x = (idx + (k == 1 ? 0.5 : 0.0)) * SPACING;
	return vec3(x, wireY(k, x) - 0.55, 0.4);
}

// Bulb: an egg (narrow end down) hanging from a small socket.
float bulbD(vec3 p, vec3 c) {
	vec3 q = p - c;
	float egg = length(q * vec3(1.0, 0.72 + 0.12 * step(q.y, 0.0), 1.0)) - 0.31;
	float socket = max(length(q.xz) - 0.15, abs(q.y - 0.4) - 0.11);
	return min(egg * 0.8, socket);
}

float nearestIdx(int k, float x) { return floor(x / SPACING - (k == 1 ? 0.5 : 0.0) + 0.5); }

float extraD(vec3 p) {
	float d = 1e9;
	for (int k = 0; k < 3; k++) {
		float i0 = nearestIdx(k, p.x);
		for (int j = -1; j <= 1; j++) d = min(d, bulbD(p, bulbCentre(k, i0 + float(j))));
	}
	return d;
}

vec3 shadeExtra(vec3 p, vec3 n, vec3 v) {
	// Find which bulb this is.
	float best = 1e9;
	int bk = 0;
	float bi = 0.0;
	for (int k = 0; k < 3; k++) {
		float i0 = nearestIdx(k, p.x);
		for (int j = -1; j <= 1; j++) {
			float d = bulbD(p, bulbCentre(k, i0 + float(j)));
			if (d < best) { best = d; bk = k; bi = i0 + float(j); }
		}
	}
	vec3 q = p - bulbCentre(bk, bi);
	if (q.y > 0.28) {
		// The socket: dark green plastic.
		return vec3(0.01, 0.03, 0.015) * (0.4 + max(dot(n, keyDir()), 0.0)) + spow(dot(n, normalize(keyDir() + v)), 40.0) * 0.15;
	}
	float on = chase(bk, bi);
	vec3 col = bulbColor(bi);
	// Glass: a hot core, colour through the body, a glossy rim and highlight.
	float core = spow(max(dot(n, v), 0.0), 2.0);
	vec3 glass = col * (0.08 + on * (0.5 + 0.9 * core));
	glass += env(reflect(-v, n)) * fresnel(n, v, 0.05) * 0.8 + spow(dot(n, normalize(keyDir() + v)), 120.0) * vec3(1.5);
	return glass;
}

vec3 capeBackground(vec2 P, float halo) {
	// Velvet: deep green, a fine pile and a sheen that falls off to the corners.
	vec3 c = mix(vec3(0.012, 0.04, 0.02), vec3(0.002, 0.008, 0.004), smoothstep(0.0, 9.0, length(P * vec2(1.0, 0.7))));
	c *= 0.8 + 0.4 * fbm3(vec3(P * 22.0, 0.0), 3);
	for (int k = 0; k < 3; k++) {
		// The wire.
		float wy = wireY(k, P.x);
		float wire = 1.0 - smoothstep(0.02, 0.04, abs(P.y - wy));
		c = mix(c, vec3(0.01, 0.025, 0.012), wire);
		// Light spilled on the velvet by the nearest bulbs.
		float i0 = nearestIdx(k, P.x);
		for (int j = -1; j <= 1; j++) {
			float idx = i0 + float(j);
			vec3 bc = bulbCentre(k, idx);
			float d = length(P - bc.xy);
			c += bulbColor(idx) * chase(k, idx) * (exp(-d * 2.2) * 0.12 + exp(-d * 0.8) * 0.02);
		}
	}
	c += motes(P, 1.3, -1.0, 0.18, 0.045) * vec3(0.8, 0.85, 0.9) * 0.4;
	c += motes(P + 5.0, 2.2, -2.0, 0.12, 0.05) * vec3(0.6) * 0.25;
	return c;
}
