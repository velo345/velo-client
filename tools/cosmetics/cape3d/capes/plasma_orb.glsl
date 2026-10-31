// Plasma Orb - a glass sphere on a dark stand, filled with writhing plasma: filaments crawl from the glowing core to
// the glass, one of them chasing a point of contact that slides round the sphere, flickering hot where it lands.

const vec3 GLOW = vec3(1.4, 0.45, 2.4);
const vec3 HOT = vec3(2.4, 1.9, 2.8);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.005, 0.003, 0.01);
const vec3 AMB1 = vec3(0.05, 0.04, 0.07);
const vec3 FABRIC = vec3(0.01, 0.006, 0.016);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO
#define OVERLAY

#include "kit.glsl"

const vec3 ORB = vec3(0.0, 1.6, 0.0);
const float ORB_R = 3.6;

float extraD(vec3 p) {
	// Only the stand is solid; the glass is drawn in the overlay.
	vec3 q = p - vec3(0.0, ORB.y - ORB_R - 1.25, 0.0);
	float base = max(length(q.xz) - (1.9 - q.y * 0.5), abs(q.y) - 1.1);
	return base;
}

vec3 shadeExtra(vec3 p, vec3 n, vec3 v) {
	// The stand: dark gunmetal turned on a lathe - fine rings, a polished band, a bright lip under the glass.
	float y = p.y - (ORB.y - ORB_R - 1.25);
	float rings = 0.85 + 0.15 * sin(y * 60.0);
	vec3 col = vec3(0.014, 0.013, 0.017) * rings + env(reflect(-v, n)) * fresnel(n, v, 0.25) * 1.1;
	col += spow(dot(n, normalize(keyDir() + v)), 60.0) * vec3(0.5);
	col += (1.0 - smoothstep(0.0, 0.06, abs(y - 0.25))) * vec3(0.2, 0.18, 0.25);
	col += (1.0 - smoothstep(0.0, 0.05, abs(y - 1.02))) * GLOW * 0.4;
	// The orb's light on the top of the stand.
	col += GLOW * 0.05 * smoothstep(0.3, 1.0, n.y);
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = mix(vec3(0.01, 0.006, 0.016), vec3(0.002, 0.001, 0.004), smoothstep(0.0, 9.0, length(P - ORB.xy)));
	c += exp(-max(length(P - ORB.xy) - ORB_R, 0.0) * 0.8) * GLOW * 0.05;
	return c;
}

// One filament from `a` to `end`: a smooth wandering path (low-frequency noise, pinned at both ends) drawn as a
// bright core in a softer sheath and a wide glow. Every motion is a whole number of turns per loop.
float strand(vec2 P, vec2 a, vec2 end, float seed, float width, float wobble) {
	vec2 ab = end - a;
	float len = length(ab);
	vec2 dir = ab / len;
	vec2 nrm = vec2(-dir.y, dir.x);
	float t = clamp(dot(P - a, dir) / len, 0.0, 1.0);
	vec3 lo = loopOffset(0.5, 2, seed * 0.13);
	float wig = (fbm3(vec3(t * 2.6, seed, 0.0) + lo, 3) - 0.5) * len * wobble * sin(t * PI);
	wig += (noise3(vec3(t * 9.0, seed + 5.0, 0.0) + lo * 2.0) - 0.5) * 0.12 * sin(t * PI);
	float d = abs(dot(P - a, nrm) - wig);
	float along = dot(P - a, dir);
	if (along < -0.05 || along > len + 0.05) d = 1e3;
	// Thinner towards the glass.
	float w = width * mix(1.4, 0.6, t);
	return exp(-d * d / (w * w)) + exp(-d * d / (w * w * 10.0)) * 0.3 + exp(-d / (w * 9.0)) * 0.07;
}

// A filament with two short forks branching off near its end.
float filament(vec2 P, vec2 end, float seed, float width) {
	vec2 a = ORB.xy;
	float f = strand(P, a, end, seed, width, 0.35);
	vec2 dir = normalize(end - a);
	vec2 fork = mix(a, end, 0.68 + 0.08 * sin(seed));
	for (int i = 0; i < 2; i++) {
		float s = i == 0 ? -1.0 : 1.0;
		float ang = s * (0.45 + 0.15 * sin(TAU * uPhase * 3.0 + seed + float(i)));
		vec2 bd = vec2(dir.x * cos(ang) - dir.y * sin(ang), dir.x * sin(ang) + dir.y * cos(ang));
		vec2 tip = fork + bd * length(end - fork) * 0.9;
		tip = ORB.xy + normalize(tip - ORB.xy) * min(length(tip - ORB.xy), ORB_R - 0.05);
		f += strand(P, fork, tip, seed + 11.0 + float(i), width * 0.6, 0.5) * 0.6;
	}
	return f;
}

vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo) {
	vec2 q = P - ORB.xy;
	float r = length(q);
	float inOrb = 1.0 - smoothstep(ORB_R - 0.02, ORB_R + 0.02, r);
	float z = sqrt(max(ORB_R * ORB_R - r * r, 0.0)) / ORB_R;   // how far the glass bulges towards you here
	// Inside: a violet gas swirling round the core, densest in the middle; what's behind shows through, bent.
	vec2 sw = vec2(q.x * cos(r * 0.4) - q.y * sin(r * 0.4), q.x * sin(r * 0.4) + q.y * cos(r * 0.4));
	float gas = fbm3(vec3(sw * 0.7, 0.0) + loopOffset(0.5, 1, 0.0), 5);
	vec3 inside = vec3(0.01, 0.004, 0.02) + spow(gas, 2.0) * GLOW * 0.12 * (0.4 + 0.6 * z) + exp(-r * 0.7) * GLOW * 0.1;
	col = mix(col, inside, inOrb * 0.92);
	// Filaments: nine wandering ends, each drifting a whole number of turns per loop, flickering.
	float pl = 0.0;
	for (int i = 0; i < 9; i++) {
		float fi = float(i);
		float a = TAU * (fi / 9.0 + uPhase * (mod(fi, 2.0) < 0.5 ? 1.0 : -1.0)) + 0.4 * sin(TAU * uPhase * 2.0 + fi);
		vec2 end = ORB.xy + vec2(cos(a), sin(a)) * (ORB_R - 0.06);
		float flick = 0.75 + 0.25 * sin(TAU * uPhase * (5.0 + mod(fi, 3.0)) + fi);
		pl += filament(P, end, fi * 3.7, 0.028) * flick;
		// Where each lands on the glass: a small soft corona.
		col += exp(-length(P - end) * 6.0) * GLOW * 0.35 * flick * inOrb;
	}
	// The touch: one fat, bright filament to a point sliding round the glass once a loop.
	float ta = TAU * uPhase + 1.2;
	vec2 touch = ORB.xy + vec2(cos(ta), sin(ta)) * (ORB_R - 0.03);
	float main = filament(P, touch, 31.0, 0.055);
	col += inOrb * (pl * mix(GLOW, HOT, 0.35) * 0.7 + main * mix(GLOW, HOT, 0.6) * 1.1);
	col += exp(-length(P - touch) * 2.5) * HOT * 0.6 * (0.85 + 0.15 * sin(TAU * uPhase * 9.0));
	// The core: a glowing electrode ball with a hot centre.
	col += (exp(-r * 5.0) * 2.2 + exp(-r * 1.6) * 0.3) * mix(GLOW, HOT, 0.7);
	col = mix(col, HOT * 1.2, 1.0 - smoothstep(0.32, 0.36, r));
	// Glass: Fresnel - clear in the middle, bright towards the rim - and two window reflections curved over the bulge.
	float fres = spow(1.0 - z, 3.0);
	col += inOrb * fres * vec3(0.3, 0.25, 0.42) * 0.5;
	col += exp(-abs(r - ORB_R) * 26.0) * vec3(0.5, 0.45, 0.6) * 0.6;
	vec2 w1 = q - vec2(-1.35, 1.55);
	col += inOrb * exp(-pow2(length(w1 * vec2(1.0, 1.5)) - 0.85) * 40.0) * step(w1.x, 0.35) * step(-0.4, w1.y) * vec3(0.35, 0.32, 0.4);
	col += inOrb * exp(-dot(q - vec2(-1.6, 1.9), q - vec2(-1.6, 1.9)) * 6.0) * vec3(0.6);
	col += inOrb * exp(-pow2(length((q - vec2(1.6, -1.8)) * vec2(1.0, 1.4)) - 0.5) * 30.0) * vec3(0.1, 0.09, 0.14);
	// Light from the orb pooling on the top of the stand.
	col += exp(-length((P - vec2(0.0, ORB.y - ORB_R - 0.15)) * vec2(0.6, 3.0)) * 1.5) * GLOW * 0.25;
	return col;
}
