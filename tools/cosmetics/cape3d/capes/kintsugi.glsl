// Kintsugi - smooth black glazed ceramic, mended with gold: a few long, fine cracks branch across it, raised a little
// and polished; a glint of light runs down the gold once a loop while a soft reflection slides over the glaze.

const vec3 GLOW = vec3(2.2, 1.45, 0.45);
const vec3 HOT = vec3(2.6, 2.2, 1.4);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.006, 0.005, 0.004);
const vec3 AMB1 = vec3(0.05, 0.045, 0.04);
const vec3 FABRIC = vec3(0.012, 0.011, 0.01);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

// Distance to a jagged crack from a to b (the same jagged path as the kit's bolts, as a distance).
float crack(vec2 P, vec2 a, vec2 b, float seed) {
	vec2 ab = b - a;
	float len = length(ab);
	vec2 dir = ab / len;
	vec2 nrm = vec2(-dir.y, dir.x);
	float t = dot(P - a, dir) / len;
	float off = (fbm3(vec3(t * 6.0, seed, 0.0), 5) - 0.5) * len * 0.32 * sin(clamp(t, 0.0, 1.0) * PI);
	float d = abs(dot(P - a, nrm) - off);
	if (t < 0.0) d = length(P - a);
	if (t > 1.0) d = length(P - b);
	return d;
}

// The break: cracks radiating out from one point of impact (above and left of centre), some forking once.
const vec2 IMPACT = vec2(-1.3, 2.4);
float cracks(vec2 P) {
	float d = 1e9;
	const int RAYS = 7;
	for (int i = 0; i < RAYS; i++) {
		float h = hash11(float(i) * 5.3 + 0.7);
		float a = TAU * (float(i) + 0.25 + 0.5 * h) / float(RAYS) + 0.3;
		float len = 7.5 + 5.0 * h;
		vec2 end = IMPACT + vec2(cos(a), sin(a)) * len;
		d = min(d, crack(P, IMPACT, end, float(i) + 1.0));
		// A fork from a point along the crack, bending off to one side.
		if (h > 0.35) {
			vec2 mid = mix(IMPACT, end, 0.35 + 0.3 * h);
			float fa = a + (h > 0.65 ? 0.6 : -0.6);
			d = min(d, crack(P, mid, mid + vec2(cos(fa), sin(fa)) * (2.5 + 2.5 * h), float(i) + 11.0));
		}
	}
	return d;
}

vec3 capeBackground(vec2 P, float halo) {
	float d = cracks(P);
	// The glaze: near black with a faint warm depth, a soft broad reflection sliding diagonally over it.
	vec3 col = mix(vec3(0.012, 0.011, 0.01), vec3(0.003, 0.003, 0.003), smoothstep(0.0, 10.0, length(P - vec2(-1.5, 3.0))));
	col *= 0.9 + 0.2 * fbm3(vec3(P * 2.0, 1.0), 3);
	float sx = P.x * 0.6 + P.y * 0.4 - (-9.0 + 18.0 * uPhase);
	col += exp(-sx * sx * 0.35) * vec3(0.035, 0.033, 0.03);
	col += exp(-pow2(length(P - vec2(-2.8, 5.0))) * 0.4) * vec3(0.03);
	// A dark groove beside each crack, then the raised gold seam with its own little bevel.
	float w = 0.055;
	col *= 1.0 - (1.0 - smoothstep(w, w * 2.5, d)) * 0.6;
	float gold = 1.0 - smoothstep(w * 0.6, w, d);
	float bevel = clamp(1.0 - d / w, 0.0, 1.0);
	vec3 f0 = vec3(1.0, 0.72, 0.32);
	vec3 g = f0 * (0.25 + 0.75 * bevel) * 0.55;
	float glint = exp(-pow2(P.y - (10.0 - 20.0 * uPhase)) * 0.5);
	g += glint * HOT * 1.3 * bevel;
	col = mix(col, g, gold);
	col += (1.0 - smoothstep(0.0, w * 5.0, d)) * GLOW * 0.025 * (0.5 + glint);
	return col;
}
