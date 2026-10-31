// Eclipse - a black moon in front of the sun, small in a wide dark sky: the corona's streamers glow and pulse round
// it, prominences flicker at the rim, faint stars behind.

const vec3 GLOW = vec3(2.4, 1.55, 0.75);
const vec3 HOT = vec3(2.8, 2.5, 2.1);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.004, 0.003, 0.003);
const vec3 AMB1 = vec3(0.02, 0.015, 0.01);
const vec3 FABRIC = vec3(0.012, 0.009, 0.007);
const vec2 LOGO_C = vec2(0.0, 1.2);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

const vec3 MOON_C = vec3(0.0, 1.8, 0.0);
const float MOON_R = 2.1;

float extraD(vec3 p) { return length(p - MOON_C) - MOON_R; }

vec3 shadeExtra(vec3 p, vec3 n, vec3 v) {
	// Near black, a faint grey surface, the corona's light wrapping round the limb.
	float craters = fbm3(p * 1.4, 5);
	vec3 col = vec3(0.006, 0.0055, 0.005) * (0.6 + 0.8 * craters);
	float rim = spow(1.0 - max(dot(n, v), 0.0), 3.0);
	col += rim * GLOW * 0.35;
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	vec2 q = P - MOON_C.xy;
	float r = length(q);
	float a = atan(q.y, q.x);
	vec3 c = vec3(0.002, 0.0018, 0.003);
	c += sparkles(P, 1.6, 0.0, 0.07) * vec3(0.8, 0.85, 1.0) * 0.35 * smoothstep(MOON_R + 1.5, MOON_R + 5.0, r);
	// Corona: a bright inner ring and long streamers of noise sampled round the circle, breathing twice a loop.
	float x = max(r - MOON_R, 0.0);
	float breathe = 0.85 + 0.15 * sin(TAU * uPhase * 2.0);
	vec3 circle = vec3(cos(a), sin(a), 0.0);
	float streams = fbm3(circle * 2.2 + vec3(0.0, 0.0, x * 0.5), 5);
	float fine = fbm3(circle * 7.0 + vec3(0.0, 0.0, x * 1.3), 4);
	float corona = exp(-x * 3.6) * 1.4 + exp(-x * (0.8 + 1.3 * (1.0 - streams))) * spow(streams, 2.0) * 1.3;
	corona *= 0.7 + 0.5 * fine;
	c += corona * mix(GLOW, HOT, exp(-x * 3.0)) * 0.45 * breathe;
	// A thin red chromosphere hugging the limb, and a few prominences.
	c += exp(-x * 14.0) * vec3(2.2, 0.35, 0.2) * 0.5;
	float prom = spow(fbm3(circle * 5.0, 4), 4.0) * (0.6 + 0.4 * sin(TAU * uPhase * 3.0 + a * 4.0));
	c += exp(-x * 6.0) * prom * vec3(2.4, 0.5, 0.3) * 0.9;
	return c;
}
