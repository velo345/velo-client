// Velo Client Owner - the owner's own cloak (private upload, never a store item): the sharp Velo logo in obsidian with
// crimson edges that beat like a heart (lub-dub, twice a loop), slow crimson rays turning behind it, embers rising from
// the hem, OWNER in dark red chrome under a thin rule with a white shine crossing it once a loop. Black and deep red.
// @text "OWNER"
// @text2 "VELO CLIENT"

const vec3 GLOW = vec3(2.0, 0.10, 0.12);
const vec3 HOT = vec3(2.6, 0.75, 0.6);
const vec3 RED = vec3(2.1, 0.05, 0.08);
const vec3 FACE0 = vec3(0.003, 0.0, 0.0005);
const vec3 FACE1 = vec3(0.05, 0.004, 0.007);
const vec3 AMB0 = vec3(0.004, 0.0, 0.001);
const vec3 AMB1 = vec3(0.05, 0.004, 0.008);
const vec3 FABRIC = vec3(0.012, 0.002, 0.003);
const vec2 LOGO_C = vec2(0.0, 1.7);
const float LOGO_S = 8.0;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define LOGO_EDGE
#define OVERLAY

#include "kit.glsl"

uniform sampler2D uText2;
float textB(vec2 q) {
	vec2 uv = vec2(q.x + 0.5, 0.5 - q.y * 8.0);
	vec2 c = clamp(uv, 0.0, 1.0);
	return texture(uText2, c).r + length((uv - c) * vec2(1.0, 0.125));
}

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

// lub-dub: two quick beats, then rest - twice per loop
float heart() {
	float t = fract(uPhase * 2.0);
	return exp(-pow2((t - 0.08) * 28.0)) + 0.65 * exp(-pow2((t - 0.22) * 28.0));
}

vec3 logoEdge(vec3 lp, float t, float pl) {
	float h = heart();
	vec3 col = mix(HOT, GLOW * 0.55, smoothstep(0.0, 0.7, t));
	return col * (0.45 + 0.6 * pl + 1.3 * h);
}

vec3 capeBackground(vec2 P, float halo) {
	vec2 q = P - LOGO_C;
	float r = length(q * vec2(1.0, 0.85));
	vec3 c = mix(vec3(0.022, 0.002, 0.003), vec3(0.002, 0.0, 0.0005), smoothstep(0.0, 9.0, r));
	c *= 0.85 + 0.3 * fbm3(vec3(P * 28.0, 0.0), 2);
	float h = heart();
	c += halo * GLOW * (0.05 + 0.08 * h);
	// slow rays turning behind the logo (12 rays, one twelfth of a turn per loop -> seamless)
	float a = atan(q.y, q.x) + TAU * uPhase / 12.0;
	float rays = pow(1e-6 + 0.5 + 0.5 * cos(a * 12.0), 14.0);
	c += rays * RED * 0.035 * exp(-r * 0.28) * (0.7 + 0.5 * h);
	// embers rising from the hem, one cell per loop
	vec2 g = vec2(P.x * 1.4, P.y * 0.9 + 9.0 * uPhase * 0.0 - uPhase * 3.0);
	vec2 cell = floor(g);
	vec2 hh = hash22(cell);
	vec2 f = fract(g) - (0.2 + 0.6 * hh);
	float low = smoothstep(2.0, -7.5, P.y);
	float tw = 0.5 + 0.5 * sin(TAU * (uPhase * (1.0 + floor(hh.y * 3.0)) + hh.x));
	c += (1.0 - smoothstep(0.0, 0.05, length(f * vec2(1.0, 0.6)))) * step(0.78, hash21(cell + 7.3)) * mix(RED, HOT, hh.x) * (0.2 + 0.8 * tw) * 0.55 * low;
	// thin rule under the logo
	float rule = (1.0 - smoothstep(0.0, 0.025, abs(P.y + 3.3))) * (1.0 - smoothstep(2.4, 3.0, abs(P.x)));
	c += rule * RED * (0.3 + 0.5 * h);
	// OWNER in dark red chrome, a white shine crossing it once a loop
	float tw2 = 12.5;
	vec2 tq = (P - vec2(0.0, -4.75)) / tw2;
	float td = text2(tq) * tw2;
	float letters = 1.0 - smoothstep(-0.006, 0.006, td);
	vec3 chrome = mix(vec3(0.10, 0.005, 0.01), vec3(0.75, 0.06, 0.08), smoothstep(-5.0, -4.3, P.y));
	float sx = P.x + (P.y + 4.75) * 0.4 - (-6.0 + 12.0 * fract(uPhase + 0.35));
	chrome += exp(-sx * sx * 6.0) * vec3(1.1, 0.75, 0.7);
	float edge = (1.0 - smoothstep(0.0, 0.02, abs(td))) * 0.6;
	c = mix(c, chrome, letters) + edge * RED * 0.3;
	// VELO CLIENT, small and spaced, above OWNER - dim silver with a faint red underglow
	float tw3 = 6.2;
	vec2 tq3 = (P - vec2(0.0, -3.85)) / tw3;
	float l3 = 1.0 - smoothstep(-0.004, 0.004, textB(tq3) * tw3);
	c = mix(c, vec3(0.42, 0.40, 0.42) + RED * 0.06 * (0.5 + h), l3);
	return c;
}

// (no ring washing out from the logo any more - owner's call, 2026-11-09)
vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo) {
	return col;
}
