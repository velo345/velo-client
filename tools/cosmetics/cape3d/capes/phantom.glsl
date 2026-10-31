// Phantom - a hooded wraith of dark smoke floating in the void: it bobs and its robe billows, the hem tears away
// into rising sparks, smoke tendrils swirl round it, its eyes flicker and once a loop a spectral shockwave bursts
// out of them.

const vec3 GLOW = vec3(1.2, 0.45, 2.4);
const vec3 HOT = vec3(2.2, 1.8, 2.8);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.005, 0.003, 0.01);
const vec3 AMB1 = vec3(0.03, 0.02, 0.05);
const vec3 FABRIC = vec3(0.01, 0.007, 0.016);
const vec2 LOGO_C = vec2(0.0, 1.5);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

// The figure floats up and down once a loop.
float bob() { return 0.28 * sin(TAU * uPhase); }

// The shockwave: bursts from the eyes at loop time 0.55 and races outwards.
const float WAVE_AT = 0.55;
float waveAge() { return fract(uPhase - WAVE_AT); }

// The wraith's silhouette: a pointed hood, the robe widening and billowing, tearing into rags towards the hem.
float wraith(vec2 P, float n) {
	P.y -= bob();
	// Billowing: the robe sways sideways, more the lower it hangs, a wave travelling down it.
	float sway = sin(TAU * uPhase - P.y * 0.55) * 0.35 * smoothstep(1.5, -7.0, P.y);
	P.x -= sway;
	vec2 h = P - vec2(0.0, 2.2);
	float hood = length(vec2(h.x * (1.0 + max(h.y, 0.0) * 0.28), h.y * 0.78)) - 1.95;
	float width = 1.5 + max(0.0, 1.6 - P.y) * 0.38;
	float body = max(abs(P.x) - width, P.y - 1.6);
	float robe = min(hood, body);
	// Rags: long tears that lengthen towards the hem and flutter.
	float rags = (fbm3(vec3(P.x * 1.6, 0.0, 2.0) + loopOffset(0.25, 1, 0.0), 3) - 0.45) * smoothstep(-1.0, -7.5, P.y) * 5.0;
	return robe + (n - 0.5) * 0.9 + max(rags, 0.0) + smoothstep(-4.0, -8.0, P.y) * 2.5;
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = vec3(0.003, 0.002, 0.005);
	vec2 eyesC = vec2(0.0, 2.35 + bob());
	// A faint violet glow behind the figure that swells with the shockwave.
	float age = waveAge();
	float burst = exp(-age * 7.0);
	c += exp(-length((P - eyesC) * vec2(0.5, 0.35)) * 0.9) * GLOW * (0.03 + 0.08 * burst);

	float n = flowNoise(vec3(P * vec2(0.6, 0.4), 0.0), vec3(0.0, 1.6, 0.0), 6);
	float w = wraith(P, n);
	float inside = 1.0 - smoothstep(-0.2, 0.25, w);
	vec3 smoke = vec3(0.012, 0.008, 0.02) * (0.6 + 0.8 * n);
	c = mix(c, smoke, inside);
	// The edge light flickers and runs down the robe.
	float run = 0.6 + 0.6 * spow(0.5 + 0.5 * sin(TAU * uPhase * 3.0 + P.y * 1.3), 3.0);
	c += exp(-abs(w) * 6.0) * GLOW * 0.1 * (0.5 + 0.8 * n) * run * smoothstep(-7.5, 1.0, P.y);

	// Smoke tendrils swirling round the figure: a spiral field that turns once a loop.
	vec2 sq = (P - vec2(0.0, -0.5 + bob())) * vec2(1.0, 0.55);
	float sr = length(sq);
	float sa = atan(sq.y, sq.x);
	float spiral = sin(sa * 2.0 + sr * 1.6 - TAU * uPhase) * 0.5 + 0.5;
	float tendril = spow(spiral, 10.0) * exp(-pow2(sr - 3.6) * 0.35) * (0.4 + 0.8 * n);
	c += tendril * GLOW * 0.07 * (1.0 - inside * 0.7);

	// Wisps peeling off and rising up the cape.
	float wisp = flowNoise(vec3(P.x * 0.9, P.y * 0.5, 3.0), vec3(0.0, 2.2, 0.0), 5);
	float ridge = spow(1.0 - abs(2.0 * wisp - 1.0), 6.0) * smoothstep(-6.0, 6.0, P.y) * (1.0 - inside);
	c += ridge * vec3(0.012, 0.007, 0.022) * 0.8;

	// The hem dissolving: sparks rising from the bottom of the robe, fast, fading as they climb.
	float hem = smoothstep(-2.0, -7.0, P.y);
	c += motes(P, 2.2, 3.0, 0.25, 0.06) * GLOW * 0.9 * hem * smoothstep(1.6, -0.5, abs(P.x) - 1.8);
	c += motes(P, 1.2, 1.0, 0.1, 0.045) * GLOW * 0.35;

	// The face: a void under the hood, two eyes that flicker and flare with the burst.
	vec2 f = P - eyesC;
	float face = 1.0 - smoothstep(0.9, 1.5, length(f * vec2(1.0, 1.2)));
	c *= 1.0 - face * 0.92;
	float flicker = 0.75 + 0.25 * sin(TAU * uPhase * 7.0) * sin(TAU * uPhase * 3.0 + 1.0);
	float flare = flicker + 1.4 * burst;
	for (int i = 0; i < 2; i++) {
		vec2 e = f - vec2(i == 0 ? -0.5 : 0.5, 0.0);
		float eye = exp(-length(e * vec2(1.0, 2.2)) * 9.0) * 2.0 + exp(-length(e) * 2.2) * 0.25;
		// A thin streak trailing off each eye, sideways.
		eye += exp(-abs(e.y) * 22.0) * exp(-abs(e.x) * 2.2) * 0.25 * (0.4 + burst);
		c += eye * mix(GLOW, HOT, 0.4) * flare;
	}

	// The shockwave: a thin bright ring and a soft wake, racing out from the eyes and fading.
	float rr = length((P - eyesC) * vec2(1.0, 0.85));
	float radius = age * 14.0;
	float ring = exp(-pow2((rr - radius) * 5.0)) * exp(-age * 4.0);
	float wake = smoothstep(radius, radius - 1.5, rr) * smoothstep(radius - 3.0, radius - 1.5, rr) * exp(-age * 5.0);
	c += (ring * 1.3 + wake * 0.15) * mix(GLOW, HOT, 0.3);
	return c;
}
