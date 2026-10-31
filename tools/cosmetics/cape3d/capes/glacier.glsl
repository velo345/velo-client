// Glacier - the logo carved from clear blue ice, frost clouds deep inside it, its edges catching white light; frost
// creeps in from the corners of a frozen night, a shimmer sweeps over it and snow drifts down.

const vec3 GLOW = vec3(0.5, 0.95, 2.0);
const vec3 HOT = vec3(2.3, 2.5, 2.7);
const vec3 FACE0 = vec3(0.02, 0.06, 0.11);
const vec3 FACE1 = vec3(0.12, 0.25, 0.38);
const vec3 AMB0 = vec3(0.01, 0.025, 0.05);
const vec3 AMB1 = vec3(0.06, 0.12, 0.2);
const vec3 FABRIC = vec3(0.01, 0.018, 0.03);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.6;
const float LOGO_TH = 0.4;
const float LOGO_CH = 0.15;
#define LOGO_FACE

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

vec3 logoFace(vec3 lp, vec3 n, vec3 v, float din, float pl) {
	// Clear ice: deep blue at the core, paler towards the edges; frost clouds seen with parallax; glints.
	vec3 col = mix(FACE0, FACE1, smoothstep(0.6, 0.0, din) * 0.6 + smoothstep(-4.0, 5.0, lp.y) * 0.4);
	float frost = fbm3(vec3((lp.xy + v.xy * 1.5) * 1.4, 4.0), 5);
	col += spow(frost, 3.0) * vec3(0.3, 0.5, 0.7) * 0.6;
	col += sparkles(lp.xy, 3.0, 0.0, 0.08) * HOT * 0.5;
	col += env(reflect(-v, n)) * fresnel(n, v, 0.06) * 1.1 + spow(dot(n, normalize(keyDir() + v)), 260.0) * vec3(2.2);
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	vec2 q = P - LOGO_C;
	vec3 c = mix(vec3(0.008, 0.02, 0.042), vec3(0.0015, 0.0035, 0.009), smoothstep(0.0, 8.0, length(q * vec2(1.0, 0.8))));
	c += halo * GLOW * 0.12;
	// Frost from the corners: ridged noise, thinning out towards the middle.
	vec2 corner = vec2(5.0, 8.0) - abs(P);
	float reach = exp(-min(corner.x * 1.1, corner.y * 0.9));
	float ridge = 1.0 - abs(2.0 * fbm3(vec3(P * 1.6, 1.0), 6) - 1.0);
	float frost = spow(ridge, 8.0) * smoothstep(0.12, 0.7, reach);
	float sweep = exp(-pow2((P.x * 0.5 + P.y * 0.5) - (-10.0 + 20.0 * uPhase)) * 0.25);
	c += frost * vec3(0.35, 0.6, 0.9) * (0.35 + 0.9 * sweep);
	c += sparkles(P, 2.2, 0.0, 0.05) * smoothstep(0.1, 0.5, reach) * HOT * 0.6;
	// Snow falling, one cell per loop.
	c += motes(P, 1.2, -1.0, 0.3, 0.045) * vec3(0.8, 0.9, 1.0) * 0.5;
	c += motes(P + 7.1, 2.4, -2.0, 0.2, 0.06) * vec3(0.6, 0.75, 0.9) * 0.25;
	return c;
}
