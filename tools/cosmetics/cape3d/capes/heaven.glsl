// Heaven - a golden halo floats above a still sea of soft clouds at dusk, turning slowly and glinting; rays of light
// fan down from above and brighten one after another, golden sparks drift slowly down through them.

const vec3 GLOW = vec3(2.4, 1.75, 0.8);
const vec3 HOT = vec3(2.8, 2.6, 2.2);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.05, 0.035, 0.03);
const vec3 AMB1 = vec3(0.35, 0.3, 0.25);
const vec3 FABRIC = vec3(0.03, 0.026, 0.024);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

const vec3 HALO_C = vec3(0.0, 3.4, 0.0);

mat3 haloRot() { return rotX(1.18 + 0.06 * sin(TAU * uPhase)) * rotZ(0.12 * sin(TAU * uPhase)); }

float extraD(vec3 p) {
	vec3 q = haloRot() * (p - HALO_C - vec3(0.0, 0.15 * sin(TAU * uPhase), 0.0));
	return length(vec2(length(q.xy) - 2.6, q.z)) - 0.2;
}

vec3 shadeExtra(vec3 p, vec3 n, vec3 v) {
	vec3 L = keyDir();
	vec3 f0 = vec3(1.0, 0.75, 0.35);
	vec3 col = env(reflect(-v, n)) * f0 * 0.9 + f0 * max(dot(n, L), 0.0) * 0.35;
	col += spow(dot(n, normalize(L + v)), 80.0) * HOT * 1.4;
	// It glows from within, a bright band running round it once a loop.
	vec3 q = haloRot() * (p - HALO_C);
	float run = spow(0.5 + 0.5 * cos(atan(q.y, q.x) - TAU * uPhase), 20.0);
	col += GLOW * (0.35 + 1.4 * run);
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	// Dusk sky: deep blue above warming to gold at the cloud line.
	vec3 c = mix(vec3(0.08, 0.06, 0.05), vec3(0.008, 0.01, 0.03), smoothstep(-3.0, 8.0, P.y));
	// Rays fanning down from above the halo.
	vec2 src = vec2(0.0, 11.0);
	vec2 dq = P - src;
	float ang = atan(dq.x, -dq.y);
	float rays = fbm3(vec3(ang * 6.0, 0.0, 0.0), 4);
	// The shafts don't move; each brightens in turn as a slow wave passes across them.
	float turn = 0.55 + 0.45 * sin(TAU * uPhase - ang * 5.0);
	c += spow(rays, 3.0) * GLOW * 0.25 * turn * smoothstep(-6.0, 6.0, P.y) * (1.0 - smoothstep(0.0, 1.2, abs(ang)));
	// The cloud sea: layered soft noise, still, lit gold from above.
	float n = fbm3(vec3(P.x * 0.3, P.y * 0.5, 0.0), 6);
	float cloud = smoothstep(0.42, 0.75, n + (-1.5 - P.y) * 0.12);
	vec3 cloudCol = mix(vec3(0.2, 0.17, 0.16), vec3(1.4, 1.05, 0.7), smoothstep(-8.0, -1.0, P.y) * n);
	c = mix(c, cloudCol * 0.55, cloud);
	// The halo's light on the clouds and the air under it.
	c += exp(-length((P - HALO_C.xy) * vec2(0.35, 0.6))) * GLOW * 0.18;
	c += halo * 0.0;
	c += motes(P, 1.4, -1.0, 0.14, 0.04) * HOT * 0.4 * smoothstep(-4.0, 4.0, P.y);
	c += sparkles(P, 1.1, -1.0, 0.05) * HOT * 0.25;
	return c;
}
