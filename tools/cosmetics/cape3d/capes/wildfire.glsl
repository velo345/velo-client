// Wildfire - the logo as charred black stone, its edges molten orange, heat creeping up its face; flames roll up
// behind it out of the dark and embers drift into the air.

const vec3 GLOW = vec3(2.3, 0.62, 0.1);
const vec3 HOT = vec3(2.6, 1.8, 0.8);
const vec3 FACE0 = vec3(0.008, 0.004, 0.003);
const vec3 FACE1 = vec3(0.05, 0.022, 0.012);
const vec3 AMB0 = vec3(0.03, 0.008, 0.002);
const vec3 AMB1 = vec3(0.01, 0.006, 0.005);
const vec3 FABRIC = vec3(0.02, 0.008, 0.005);
const vec2 LOGO_C = vec2(0.0, 1.4);
const float LOGO_S = 8.6;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define LOGO_FACE

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

vec3 fireRamp(float t) {
	t = clamp(t, 0.0, 1.0);
	vec3 c = mix(vec3(0.0), vec3(0.35, 0.02, 0.0), smoothstep(0.0, 0.3, t));
	c = mix(c, vec3(1.6, 0.35, 0.03), smoothstep(0.3, 0.65, t));
	c = mix(c, vec3(2.6, 1.5, 0.45), smoothstep(0.65, 0.95, t));
	return c;
}

vec3 logoFace(vec3 lp, vec3 n, vec3 v, float din, float pl) {
	// Charred stone: dark, faintly grained; heat rising from below glows through it.
	vec3 col = mix(FACE0, FACE1, smoothstep(-4.0, 5.0, lp.y));
	col *= 0.75 + 0.5 * fbm3(vec3(lp.xy * 5.0, 2.0), 4);
	float heat = flowNoise(vec3(lp.x * 0.8, lp.y * 0.6, 3.0), vec3(0.0, 2.4, 0.0), 5);
	float rise = smoothstep(4.0, -4.0, lp.y);
	col += fireRamp(heat * 0.9 * rise + 0.05) * 0.16;
	col += env(reflect(-v, n)) * fresnel(n, v, 0.04) * 0.7 + spow(dot(n, normalize(keyDir() + v)), 200.0) * vec3(1.4, 1.1, 0.9);
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = mix(vec3(0.02, 0.005, 0.002), vec3(0.002, 0.0012, 0.001), smoothstep(-8.0, 4.0, P.y));
	// Flames: scrolling noise squeezed upwards, strongest at the bottom and behind the logo, gone near the top.
	float n = flowNoise(vec3(P.x * 0.55, P.y * 0.32, 0.0), vec3(0.0, 1.5, 0.0), 6);
	float n2 = flowNoise(vec3(P.x * 1.3, P.y * 0.7, 5.0), vec3(0.0, 3.0, 0.0), 4);
	// Tongues: the noise thresholded against height, so flames lick up from the bottom and thin out by the middle.
	float h = (P.y + 8.0) / 16.0;
	float tongue = n * 0.75 + n2 * 0.35 - h * 0.95 + 0.05;
	float fire = smoothstep(0.0, 0.5, tongue) * (0.55 + 0.45 * n2);
	// Hottest where the tongues start, darker red at their tips.
	c += fireRamp(fire * 0.92) * 0.22 * smoothstep(0.7, 0.1, h);
	c += halo * GLOW * 0.14;
	// Embers rising (two cells per loop), and a few fast sparks.
	c += motes(P, 1.3, 2.0, 0.22, 0.05) * HOT * 0.7;
	c += motes(P + 3.3, 2.6, 4.0, 0.12, 0.06) * GLOW * 0.6;
	return c;
}
