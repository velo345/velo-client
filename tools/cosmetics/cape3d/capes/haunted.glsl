// Haunted - under a pale full moon, ghosts rise one after another out of a misty graveyard and fade into the night;
// the mist rolls sideways between the gravestones.

const vec3 GLOW = vec3(0.35, 1.9, 1.35);
const vec3 HOT = vec3(2.0, 2.6, 2.3);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.004, 0.008, 0.008);
const vec3 AMB1 = vec3(0.02, 0.04, 0.04);
const vec3 FABRIC = vec3(0.007, 0.012, 0.012);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const vec2 MOON = vec2(2.0, 5.2);

float sdBox2(vec2 p, vec2 b) { vec2 d = abs(p) - b; return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0); }

// The ground: a low hill with gravestones and a leaning cross. Negative inside the silhouette.
float graveyard(vec2 P) {
	float hill = P.y - (-5.6 + 0.5 * sin(P.x * 0.45 + 0.6) + 0.25 * sin(P.x * 1.3));
	float d = hill;
	vec3 stones[4] = vec3[4](vec3(-3.4, 0.55, 0.9), vec3(-0.9, 0.7, 1.25), vec3(1.7, 0.5, 0.8), vec3(3.9, 0.6, 1.0));
	for (int i = 0; i < 4; i++) {
		vec2 c = vec2(stones[i].x, -5.4 + stones[i].z * 0.5);
		vec2 q = P - c;
		q = vec2(q.x * cos(0.08 * float(i - 1)) - q.y * sin(0.08 * float(i - 1)), q.x * sin(0.08 * float(i - 1)) + q.y * cos(0.08 * float(i - 1)));
		float body = sdBox2(q + vec2(0.0, 0.2), vec2(stones[i].y, stones[i].z * 0.5));
		float top = length(q - vec2(0.0, stones[i].z * 0.3)) - stones[i].y;
		d = min(d, min(body, max(top, -q.y + stones[i].z * 0.3 - 0.01)));
	}
	vec2 cq = P - vec2(0.4, -3.9);
	cq = vec2(cq.x * cos(-0.15) - cq.y * sin(-0.15), cq.x * sin(-0.15) + cq.y * cos(-0.15));
	d = min(d, min(sdBox2(cq, vec2(0.11, 1.0)), sdBox2(cq - vec2(0.0, 0.35), vec2(0.5, 0.1))));
	return d;
}

// One ghost, `t` 0..1 through its rise: a round head, a body tapering into a waving tail. (alpha, eye mask)
vec2 ghost(vec2 P, vec2 base, float t, float seed) {
	float y = base.y + t * 11.0;
	vec2 q = P - vec2(base.x + 0.5 * sin(t * TAU + seed), y);
	float size = 0.9 + 0.2 * hash11(seed);
	q /= size;
	float head = length(q) - 0.75;
	float tailY = clamp(-q.y, 0.0, 2.6);
	float width = 0.75 * (1.0 - tailY / 2.8) + 0.05;
	float wave = sin(q.y * 4.0 + TAU * t * 3.0 + seed) * 0.18 * tailY / 2.6;
	float body = max(abs(q.x - wave) - width, max(q.y, -q.y - 2.6));
	float shape = min(head, body);
	float alpha = (1.0 - smoothstep(-0.25, 0.08, shape)) * spow(sin(PI * t), 1.2) * smoothstep(2.6, 1.4, tailY + 0.0);
	alpha = (1.0 - smoothstep(-0.25, 0.08, shape)) * spow(sin(PI * t), 1.2) * (1.0 - smoothstep(1.2, 2.6, tailY));
	float eyes = exp(-length((q - vec2(-0.26, 0.12)) * vec2(1.6, 1.0)) * 9.0) + exp(-length((q - vec2(0.26, 0.12)) * vec2(1.6, 1.0)) * 9.0);
	float mouth = exp(-length((q - vec2(0.0, -0.25)) * vec2(1.4, 1.0)) * 11.0);
	return vec2(alpha, clamp(eyes + mouth * 0.7, 0.0, 1.0));
}

vec3 capeBackground(vec2 P, float halo) {
	// Night sky: deep blue-green, lighter round the moon.
	vec3 c = mix(vec3(0.006, 0.014, 0.018), vec3(0.002, 0.004, 0.007), smoothstep(-6.0, 8.0, P.y));
	c += sparkles(P, 1.5, 0.0, 0.05) * vec3(0.7, 0.9, 0.9) * 0.3 * smoothstep(-2.0, 3.0, P.y);
	float mr = length(P - MOON);
	c += exp(-mr * 0.5) * vec3(0.12, 0.2, 0.2) * 0.5;
	float disc = 1.0 - smoothstep(1.75, 1.8, mr);
	vec3 moonCol = vec3(0.75, 0.9, 0.85) * (0.75 + 0.35 * fbm3(vec3((P - MOON) * 1.6, 2.0), 5)) * (1.0 - 0.35 * smoothstep(0.8, 1.8, mr));
	c = mix(c, moonCol, disc);
	// Ghosts, three of them, each rising once a loop, a third of a loop apart.
	vec3 ghosts = vec3(0.0);
	vec2 bases[3] = vec2[3](vec2(-2.6, -5.0), vec2(0.9, -5.2), vec2(3.4, -4.9));
	for (int i = 0; i < 3; i++) {
		float t = fract(uPhase + float(i) / 3.0);
		vec2 g = ghost(P, bases[i], t, float(i) * 2.3);
		vec3 body = mix(GLOW * 0.32, HOT * 0.25, 0.3) * g.x;
		ghosts += body * (1.0 - g.y * 0.9);
		ghosts += GLOW * 0.08 * g.x * exp(-0.0);
	}
	c += ghosts;
	// Ground fog rolling sideways through the graveyard (two sweeps of its width per loop).
	float fogN = flowNoise(vec3(P.x * 0.35, P.y * 0.9, 0.0), vec3(4.0, 0.0, 0.0), 5);
	float fog = smoothstep(-1.5, -5.5, P.y) * (0.35 + 0.65 * fogN);
	c += fog * vec3(0.06, 0.13, 0.12) * 0.7;
	// The silhouette, with a thin rim of moonlight along its top edge.
	float g = graveyard(P);
	c = mix(c, vec3(0.0015, 0.002, 0.002), 1.0 - smoothstep(-0.02, 0.02, g));
	// Fog drifting in front of the stones' feet only.
	c += (1.0 - smoothstep(-0.02, 0.02, g)) * fog * vec3(0.03, 0.06, 0.055) * smoothstep(-4.2, -6.5, P.y);
	c += exp(-abs(g) * 25.0) * step(0.0, g + 0.1) * vec3(0.1, 0.18, 0.17) * 0.6;
	return c;
}
