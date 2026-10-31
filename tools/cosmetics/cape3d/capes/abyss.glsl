// Abyss - the deep sea at night: glowing jellyfish rise through the dark, their bells pulsing as they swim, long
// tentacles trailing and swaying, bioluminescent rims shimmering; marine snow drifts down past faint shafts of light.

const vec3 GLOW = vec3(0.3, 1.5, 2.3);
const vec3 HOT = vec3(2.0, 2.4, 2.7);
const vec3 PINK = vec3(2.3, 0.6, 1.8);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.002, 0.005, 0.01);
const vec3 AMB1 = vec3(0.01, 0.03, 0.05);
const vec3 FABRIC = vec3(0.004, 0.01, 0.018);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

// Jellyfish k: rises once across the cape per loop (wrapping round), pulsing three times on the way.
vec3 jelly(vec2 P, int k) {
	float fk = float(k);
	float pulses = 3.0;
	float t = fract(uPhase + fk / 3.0);
	// Each pulse: a quick contraction that pushes it up, then a slow glide.
	float pt = fract(t * pulses);
	float contract = exp(-pt * 6.0) * (1.0 - exp(-pt * 40.0));
	float y = -11.0 + 22.0 * t + sin(TAU * t * pulses) * 0.15;
	float x = (fk - 1.0) * 3.1 + 0.6 * sin(TAU * t + fk * 2.0);
	float size = 1.15 + 0.35 * hash11(fk + 2.0);
	vec2 q = (P - vec2(x, y)) / size;
	vec3 col = vec3(0.0);
	vec3 tint = k == 1 ? PINK : GLOW;
	// Bell: a dome that squashes wider when it contracts.
	vec2 bq = q / vec2(1.0 + 0.18 * contract, 1.0 - 0.22 * contract);
	float dome = length(bq * vec2(1.0, 1.25)) - 1.0;
	float bell = (1.0 - smoothstep(-0.04, 0.04, dome)) * step(-0.25, bq.y);
	float rim = exp(-abs(dome) * 16.0) * step(-0.25, bq.y);
	float frill = exp(-pow2((bq.y + 0.25) * 14.0)) * smoothstep(1.05, 0.6, abs(bq.x)) * (0.6 + 0.4 * sin(bq.x * 30.0 + TAU * t * 6.0));
	float inner = spow(1.0 - clamp(-dome, 0.0, 1.0), 2.0);
	col += bell * tint * (0.06 + 0.12 * inner) + rim * tint * 0.6 + frill * tint * 0.4;
	// The gonads: four soft glowing lobes inside the bell.
	for (int i = 0; i < 4; i++) {
		float a = PI * (0.2 + 0.2 * float(i));
		col += exp(-length(bq - vec2(cos(a) * 0.45, 0.15 + sin(a) * 0.25)) * 9.0) * tint * 0.25 * bell;
	}
	// Tentacles: thin curtains trailing below, swaying in a wave that runs down them.
	for (int i = 0; i < 7; i++) {
		float fx = (float(i) - 3.0) * 0.26;
		float down = -0.25 - q.y;
		float sway = sin(down * 2.2 - TAU * t * pulses + float(i)) * 0.18 * down * 0.4;
		float d = abs(q.x - fx * (1.0 + down * 0.05) - sway);
		float len = 3.8 + 1.2 * hash11(float(i) + fk * 7.0);
		float on = step(0.0, down) * (1.0 - smoothstep(len * 0.6, len, down));
		col += exp(-d * d / 0.0012) * on * tint * 0.35 * (0.5 + 0.5 * exp(-down * 0.3));
	}
	// Two long oral arms in the middle, frilled.
	float down = -0.2 - q.y;
	float arm = abs(abs(q.x) - 0.12 - 0.08 * sin(down * 5.0 - TAU * t * 2.0));
	col += exp(-arm * arm / 0.004) * step(0.0, down) * (1.0 - smoothstep(1.8, 2.8, down)) * tint * 0.3;
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = mix(vec3(0.004, 0.012, 0.025), vec3(0.0005, 0.0015, 0.004), smoothstep(8.0, -8.0, P.y));
	// Faint shafts of light from far above.
	float sh = fbm3(vec3(P.x * 0.8 + P.y * 0.15, 0.0, 0.0) + loopOffset(0.15, 1, 0.0), 3);
	c += spow(sh, 4.0) * vec3(0.01, 0.03, 0.05) * smoothstep(-6.0, 8.0, P.y);
	// Marine snow drifting down at two depths.
	c += motes(P, 2.4, -1.0, 0.2, 0.05) * vec3(0.15, 0.25, 0.3) * 0.5;
	c += motes(P + 4.0, 1.2, -1.0, 0.1, 0.05) * vec3(0.2, 0.35, 0.4) * 0.5;
	for (int k = 0; k < 3; k++) c += jelly(P, k);
	return c;
}
