// Obsidian - the logo as black volcanic glass polished to a mirror, silver-edged; an oil-slick sheen glides across
// it once a loop; behind, near-black stone with silver veins that light flows through.

const vec3 GLOW = vec3(1.25, 1.3, 1.5);
const vec3 HOT = vec3(2.4, 2.4, 2.5);
const vec3 FACE0 = vec3(0.002, 0.002, 0.003);
const vec3 FACE1 = vec3(0.02, 0.02, 0.026);
const vec3 AMB0 = vec3(0.006, 0.006, 0.008);
const vec3 AMB1 = vec3(0.05, 0.05, 0.06);
const vec3 FABRIC = vec3(0.01, 0.01, 0.012);
const vec2 LOGO_C = vec2(0.0, 0.8);
const float LOGO_S = 8.6;
const float LOGO_TH = 0.4;
const float LOGO_CH = 0.14;
#define LOGO_FACE

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

// Thin-film colours (a cosine rainbow) for the oil-slick sheen.
vec3 film(float t) { return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67))); }

vec3 logoFace(vec3 lp, vec3 n, vec3 v, float din, float pl) {
	vec3 col = mix(FACE0, FACE1, smoothstep(-4.0, 5.0, lp.y));
	col += env(reflect(-v, n)) * fresnel(n, v, 0.06) * 1.3 + spow(dot(n, normalize(keyDir() + v)), 400.0) * vec3(2.4);
	float bx = lp.x * 0.5 + (lp.y - LOGO_C.y) * 0.5 - (-8.0 + 16.0 * uPhase);
	float band = exp(-bx * bx * 0.9);
	float swirl = fbm3(vec3(lp.xy * 0.7, 1.0) + loopOffset(0.4, 1, 0.0), 4);
	col += film(swirl * 1.6 + lp.x * 0.05) * band * fresnel(n, v, 0.3) * 0.12;
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	vec2 q = P - LOGO_C;
	vec3 c = mix(vec3(0.01, 0.01, 0.012), vec3(0.0015, 0.0015, 0.002), smoothstep(0.0, 9.0, length(q * vec2(1.0, 0.8))));
	// Silver veins in the stone stay put; light flows through them - pulses travel along the veins twice a loop.
	float n = fbm3(vec3(P * 0.35, 0.0), 6);
	float vein = spow(1.0 - abs(2.0 * n - 1.0), 40.0);
	float n2 = fbm3(vec3(P * 0.8 + 3.0, 2.0), 5);
	float fine = spow(1.0 - abs(2.0 * n2 - 1.0), 30.0) * 0.45;
	// "Distance along the vein": a smooth field that grows across the cape, so the pulses read as flowing.
	float along = P.y * 0.55 + P.x * 0.2 + fbm3(vec3(P * 0.25, 5.0), 3) * 3.0;
	float flow = spow(0.5 + 0.5 * sin(TAU * uPhase * 2.0 - along * 2.2), 6.0);
	float flow2 = spow(0.5 + 0.5 * sin(TAU * uPhase * 3.0 + along * 3.1 + 1.7), 8.0);
	c += vein * vec3(0.02, 0.02, 0.024);
	c += vein * flow * GLOW * 0.28 + fine * flow2 * GLOW * 0.14;
	c += halo * GLOW * 0.07;
	c += motes(P, 1.0, 1.0, 0.1, 0.04) * vec3(0.7) * 0.3;
	return c;
}
