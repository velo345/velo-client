// Silent Night - a sleeping pine forest under snow: aurora curtains ripple across the sky, the Christmas star turns
// its rays, a cabin's windows glow and its chimney smokes, and snow falls in three layers of depth.

const vec3 GLOW = vec3(0.3, 1.9, 1.1);
const vec3 HOT = vec3(2.6, 2.4, 2.0);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.004, 0.008, 0.014);
const vec3 AMB1 = vec3(0.02, 0.04, 0.06);
const vec3 FABRIC = vec3(0.007, 0.011, 0.018);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const vec2 STAR = vec2(-2.4, 6.3);
const vec2 CABIN = vec2(-0.4, -5.3);

// A pine: stacked triangles narrowing to the top. Negative inside.
float pine(vec2 q, float h) {
	float d = 1e9;
	for (int i = 0; i < 4; i++) {
		float fi = float(i);
		float base = h * (fi * 0.22);
		float top = base + h * 0.42;
		float w = h * (0.36 - fi * 0.07);
		vec2 t = q - vec2(0.0, base);
		float tri = max(-t.y, (abs(t.x) * (top - base) / w + t.y - (top - base)) * 0.7);
		d = min(d, tri);
	}
	return min(d, max(abs(q.x) - h * 0.04, max(-q.y - h * 0.08, q.y)));
}

// A row of pines along a ground line; returns the silhouette distance and, for snow, how close to a branch top.
float forest(vec2 P, float ground, float scale, float seed) {
	float d = P.y - ground - 0.3 * sin(P.x * 0.6 + seed);
	float cell = floor(P.x / (1.4 * scale));
	for (int k = -1; k <= 1; k++) {
		float c = cell + float(k);
		float h = hash11(c * 3.1 + seed);
		float x = (c + 0.3 + 0.4 * h) * 1.4 * scale;
		float height = (2.2 + 1.8 * h) * scale;
		d = min(d, pine(P - vec2(x, ground - 0.1), height));
	}
	return d;
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = mix(vec3(0.012, 0.02, 0.04), vec3(0.002, 0.004, 0.012), smoothstep(-4.0, 8.0, P.y));
	c += sparkles(P, 1.7, 0.0, 0.06) * vec3(0.8, 0.9, 1.0) * 0.3 * smoothstep(-1.0, 4.0, P.y);
	// Aurora: curtains whose lower edge ripples sideways, streaked with fine vertical rays.
	float t = TAU * uPhase;
	float base = 3.2 + 0.9 * sin(P.x * 0.45 + t) + 0.35 * sin(P.x * 1.25 - 2.0 * t) + 0.2 * sin(P.x * 2.7 + 3.0 * t);
	float above = P.y - base;
	float curtain = smoothstep(-0.25, 0.25, above) * exp(-max(above, 0.0) * 0.45);
	float rays = 0.55 + 0.45 * sin(P.x * 9.0 + 2.0 * sin(P.x * 0.7 + t)) * sin(P.x * 3.7 - t);
	vec3 aur = mix(vec3(0.1, 1.2, 0.55), vec3(0.6, 0.25, 1.1), smoothstep(0.0, 4.0, above));
	c += curtain * rays * aur * 0.22 * (0.7 + 0.3 * sin(t * 2.0 + P.x * 0.3));
	// The Christmas star: a bright core, eight rays turning one ray-spacing per loop.
	vec2 sq = P - STAR;
	float sr = length(sq);
	float sa = atan(sq.y, sq.x) + TAU / 8.0 * uPhase;
	float rays8s = spow(abs(cos(sa * 4.0)), 90.0) * exp(-sr * 2.6) + spow(abs(cos(sa * 4.0 + PI * 0.5)), 120.0) * exp(-sr * 4.0) * 0.5;
	c += (exp(-sr * 14.0) * 2.0 + exp(-sr * 2.4) * 0.12 + rays8s * 0.9) * HOT * (0.9 + 0.1 * sin(t * 4.0));
	// Forest: three rows, darker and bigger towards you, snow on the near branches.
	float far = forest(P, -2.6, 0.65, 1.0);
	c = mix(c, vec3(0.008, 0.016, 0.028), 1.0 - smoothstep(-0.02, 0.02, far));
	float mid = forest(P + vec2(0.7, 0.0), -4.0, 0.85, 2.0);
	c = mix(c, vec3(0.004, 0.009, 0.016), 1.0 - smoothstep(-0.02, 0.02, mid));
	// The ground: soft snow catching the sky's light.
	float ground = P.y - (-6.2 + 0.35 * sin(P.x * 0.5));
	vec3 snowCol = vec3(0.06, 0.08, 0.11) + vec3(0.03, 0.06, 0.04) * curtain;
	c = mix(c, snowCol * (0.9 + 0.2 * fbm3(vec3(P * 2.0, 0.0), 3)), 1.0 - smoothstep(-0.03, 0.03, ground));
	// The cabin: a dark block with a snowy roof, two warm windows flickering, smoke from its chimney.
	vec2 cq = P - CABIN;
	float body = max(abs(cq.x) - 1.2, abs(cq.y) - 0.7);
	float roof = max(-(cq.y - 0.7), abs(cq.x) * 0.75 + (cq.y - 0.7) - 1.05);
	float chim = max(abs(cq.x - 0.6) - 0.14, abs(cq.y - 1.55) - 0.35);
	float cabin = min(min(body, roof), chim);
	c = mix(c, vec3(0.006, 0.006, 0.008), 1.0 - smoothstep(-0.02, 0.02, cabin));
	c += (1.0 - smoothstep(0.0, 0.06, abs(roof))) * step(0.68, cq.y) * vec3(0.12, 0.14, 0.17);
	float flick = 0.85 + 0.15 * sin(t * 7.0) * sin(t * 3.0);
	for (int i = 0; i < 2; i++) {
		vec2 w = cq - vec2(i == 0 ? -0.55 : 0.4, -0.05);
		float win = max(abs(w.x) - 0.22, abs(w.y) - 0.24);
		float cross = min(abs(w.x), abs(w.y));
		c = mix(c, vec3(2.0, 1.1, 0.35) * flick * (0.6 + 0.4 * step(0.025, cross)), 1.0 - smoothstep(-0.01, 0.01, win));
		c += exp(-max(win, 0.0) * 4.0) * vec3(1.0, 0.5, 0.15) * 0.12 * flick;
	}
	vec2 sm = cq - vec2(0.6, 1.9);
	float sx = sm.x - 0.35 * sin(sm.y * 1.2 - t) * smoothstep(0.0, 3.0, sm.y);
	c += exp(-abs(sx) * (6.0 - 4.0 * smoothstep(0.0, 4.0, sm.y))) * smoothstep(0.0, 0.4, sm.y) * smoothstep(5.0, 1.0, sm.y) * vec3(0.05, 0.06, 0.07);
	// The near row in front of everything.
	float nearRow = forest(P + vec2(2.3, 0.0), -7.0, 1.15, 3.0);
	nearRow = max(nearRow, -(abs(P.x - CABIN.x) - 2.2));
	float inNear = 1.0 - smoothstep(-0.02, 0.02, nearRow);
	c = mix(c, vec3(0.002, 0.004, 0.007), inNear);
	// Snow falling at three depths: far slow and fine, near fast and big.
	c += motes(P, 2.6, -1.0, 0.35, 0.05) * vec3(0.5, 0.55, 0.6) * 0.35;
	c += motes(P + 3.0, 1.6, -2.0, 0.3, 0.05) * vec3(0.7, 0.75, 0.8) * 0.5;
	c += motes(P + 7.0, 0.9, -3.0, 0.25, 0.06) * vec3(0.9, 0.95, 1.0) * 0.6;
	return c;
}
