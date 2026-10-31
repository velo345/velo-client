// Supernova - a blue-white star swelling and throbbing inside its nebula, then it collapses to a point and explodes:
// a blinding flash, a shockwave ring tearing outward, filaments of glowing debris flying off - and from the fading
// remnant a new star kindles, so the cycle starts again.

const vec3 GLOW = vec3(0.6, 1.2, 2.4);
const vec3 HOT = vec3(2.6, 2.6, 2.8);
const vec3 EMBER = vec3(2.4, 0.7, 0.3);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.0);
const vec3 AMB1 = vec3(0.0);
const vec3 FABRIC = vec3(0.006, 0.006, 0.012);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const vec2 STAR = vec2(0.0, 1.5);
const float COLLAPSE = 0.52, BANG = 0.6;

vec3 capeBackground(vec2 P, float halo) {
	vec2 q = P - STAR;
	float r = length(q);
	float a = atan(q.y, q.x);
	float ph = uPhase;
	// Phases of the cycle.
	float grow = smoothstep(0.0, COLLAPSE, ph);                                  // the star swelling
	float collapse = smoothstep(COLLAPSE, BANG, ph);                              // shrinking to a point
	float age = clamp((ph - BANG) / (1.0 - BANG), 0.0, 1.0);                      // after the bang
	float alive = 1.0 - step(BANG, ph);
	vec3 c = vec3(0.002, 0.002, 0.005);
	c += sparkles(P, 1.6, 0.0, 0.07) * vec3(0.8, 0.85, 1.0) * 0.3;
	// The nebula around it: soft clouds, lit by whatever is burning.
	float neb = fbm3(vec3(P * 0.28, 2.0), 6);
	float neb2 = fbm3(vec3(P * 0.6 + 4.0, 5.0), 5);
	float cloud = smoothstep(0.42, 0.85, neb) * exp(-r * 0.12);
	float light = alive * (0.3 + 0.4 * grow) + step(BANG, ph) * exp(-age * 3.0) * 1.2 + age * 0.25;
	c += cloud * mix(vec3(0.06, 0.03, 0.14), vec3(0.2, 0.06, 0.08), neb2) * light;
	// The star: swelling, throbbing faster as it nears the end; then crushed to a point.
	float throb = 1.0 + 0.06 * sin(TAU * ph * (6.0 + 10.0 * grow));
	float R = mix(0.7, 1.35, grow) * throb * (1.0 - collapse * 0.97) * alive;
	// The new star kindling in the remnant towards the end of the loop (it becomes the star at the start).
	float reborn = smoothstep(0.75, 1.0, age);
	R = max(R, 0.7 * reborn);
	float body = 1.0 - smoothstep(R - 0.03, R + 0.03, r);
	float limb = spow(1.0 - clamp(r / max(R, 0.01), 0.0, 1.0), 0.4);
	vec3 starC = mix(GLOW, HOT, limb) * (1.0 + 2.0 * collapse);
	starC *= 0.85 + 0.25 * fbm3(vec3(q * 3.0 / max(R, 0.3), 1.0) + loopOffset(0.4, 2, 0.0), 4);
	c = mix(c, starC, body * step(0.01, R));
	c += exp(-max(r - R, 0.0) * 1.8) * GLOW * 0.35 * step(0.01, R) * (1.0 + collapse * 3.0);
	// The bang: a white flash, then the shockwave ring and debris.
	float flash = step(BANG, ph) * exp(-age * 22.0);
	c += flash * exp(-r * 0.9) * HOT * 1.1;
	if (ph > BANG) {
		float front = 1.0 + age * 13.0;
		float shock = exp(-pow2((r - front) * 3.5)) * (1.0 - age);
		c += shock * mix(HOT, GLOW, age) * 1.2;
		// Debris filaments: streaks along rays out from the star, rippled by noise, reddening as they cool.
		vec3 circle = vec3(cos(a), sin(a), 0.0);
		float streak = spow(fbm3(circle * 6.0 + vec3(0.0, 0.0, 3.0), 5), 3.0);
		float shell = smoothstep(front * 0.25, front * 0.85, r) * (1.0 - smoothstep(front * 0.85, front, r));
		vec3 deb = mix(mix(HOT, EMBER, smoothstep(0.0, 0.5, age)), vec3(0.6, 0.12, 0.25), smoothstep(0.5, 1.0, age));
		c += streak * shell * deb * spow(1.0 - age, 1.5) * 0.55;
		// The remnant's glow fading.
		c += exp(-r * 0.5) * EMBER * 0.1 * (1.0 - age);
	}
	return c;
}
