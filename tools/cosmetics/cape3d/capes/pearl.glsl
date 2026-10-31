// Pearl - a single big pearl on dark velvet: lustrous white nacre whose pastel colours shift as the light moves round
// it, a soft reflection beneath it, sparkles coming and going.

const vec3 GLOW = vec3(1.9, 1.75, 2.0);
const vec3 HOT = vec3(2.6, 2.5, 2.6);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.02, 0.018, 0.024);
const vec3 AMB1 = vec3(0.35, 0.33, 0.38);
const vec3 FABRIC = vec3(0.014, 0.012, 0.018);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

const vec3 PEARL_C = vec3(0.0, 0.9, 0.0);
const float PEARL_R = 3.2;

float extraD(vec3 p) { return length(p - PEARL_C) - PEARL_R; }

vec3 film(float t) { return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67))); }

vec3 shadeExtra(vec3 p, vec3 n, vec3 v) {
	vec3 L = keyDir();
	float diff = max(dot(n, L), 0.0);
	float wrap = max((dot(n, L) + 0.5) / 1.5, 0.0);
	// Nacre: soft wrapped light (it glows a little through itself), pastel orient colours that move with the light.
	vec3 base = vec3(0.78, 0.76, 0.8) * (0.12 + 0.9 * wrap);
	float swirl = fbm3(p * 0.6 + loopOffset(0.3, 1, 0.0), 4);
	vec3 orient = film(dot(n, L) * 0.8 + swirl * 0.9 + uPhase);
	vec3 col = base * mix(vec3(1.0), orient, 0.35);
	col += env(reflect(-v, n)) * fresnel(n, v, 0.04) * 0.9;
	col += spow(dot(n, normalize(L + v)), 120.0) * HOT * 0.9 + spow(dot(n, normalize(L + v)), 18.0) * vec3(0.25);
	col += spow(1.0 - max(dot(n, v), 0.0), 2.5) * orient * 0.35;
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	vec2 q = P - PEARL_C.xy;
	float r = length(q);
	// Dark velvet: a soft spotlight under the pearl, fine pile.
	vec3 c = mix(vec3(0.03, 0.026, 0.036), vec3(0.003, 0.0025, 0.004), smoothstep(0.0, 9.0, r));
	c *= 0.85 + 0.3 * fbm3(vec3(P * 18.0, 0.0), 3);
	// Its shadow and soft glow on the velvet.
	c *= 1.0 - 0.6 * exp(-pow2(max(r - PEARL_R, 0.0)) * 2.0) * step(q.y, 0.0);
	c += exp(-max(r - PEARL_R, 0.0) * 1.6) * vec3(0.12, 0.11, 0.14) * 0.6;
	c += sparkles(P, 1.5, 0.0, 0.06) * HOT * 0.45 * smoothstep(PEARL_R, PEARL_R + 2.0, r);
	return c;
}
