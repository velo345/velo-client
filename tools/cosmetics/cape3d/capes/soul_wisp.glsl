// Soul Wisp - a soul light: a pale violet core floating in the dark, three trails of light spiralling round it in 3D
// (passing in front of it and behind), faint veils of smoke rising past.

const vec3 GLOW = vec3(1.2, 0.45, 2.4);
const vec3 HOT = vec3(2.3, 1.9, 2.8);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.005, 0.003, 0.01);
const vec3 AMB1 = vec3(0.03, 0.02, 0.05);
const vec3 FABRIC = vec3(0.01, 0.007, 0.016);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO
#define OVERLAY

#include "kit.glsl"

const vec3 CORE = vec3(0.0, 1.0, 0.0);

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

vec2 raySeg(vec3 ro, vec3 rd, vec3 a, vec3 b) {
	vec3 ba = b - a, oa = ro - a;
	float baba = dot(ba, ba), bard = dot(ba, rd), baoa = dot(ba, oa), rdoa = dot(rd, oa);
	float s = clamp((baoa - rdoa * bard) / max(baba - bard * bard, 1e-6), 0.0, 1.0);
	float t = max(s * bard - rdoa, 0.0);
	return vec2(length(oa + t * rd - s * ba), t);
}

// A trail point: round the core on a tilted, slowly breathing ellipse; `a` is the angle along it.
vec3 orbit(float a, int k) {
	float tilt = float(k) * 2.094;
	float r = 2.6 + 0.35 * sin(a * 2.0 + float(k));
	vec3 q = vec3(cos(a) * r, sin(a) * r * 0.42, sin(a) * r * 0.9);
	q = rotZ(tilt + 0.35) * q;
	return CORE + q + vec3(0.0, sin(a * 3.0 + tilt) * 0.25, 0.0);
}

vec3 trails(vec3 ro, vec3 rd) {
	vec3 col = vec3(0.0);
	for (int k = 0; k < 3; k++) {
		float head = TAU * (uPhase + float(k) / 3.0);
		vec3 prev = orbit(head, k);
		const int N = 110;
		for (int j = 1; j <= N; j++) {
			float f = float(j) / float(N);
			vec3 cur = orbit(head - f * 3.2, k);
			vec2 d = raySeg(ro, rd, prev, cur);
			// Behind the core (further than its centre) the trail is dimmed by it.
			float behind = d.y > length(CORE - ro) ? 0.45 : 1.0;
			float g = 1.0 - f;
			float w = mix(0.08, 0.03, f);
			col += (exp(-d.x * d.x / (w * w)) * mix(HOT, GLOW, smoothstep(0.0, 0.4, f)) + exp(-d.x * d.x / 0.4) * GLOW * 0.05)
				* g * g * sqrt(g + 1e-6) * behind * 0.85;
			prev = cur;
		}
	}
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = vec3(0.003, 0.002, 0.006);
	vec2 q = P - CORE.xy;
	float r = length(q);
	c += exp(-r * 0.35) * GLOW * 0.03;
	// Veils of smoke drifting up, very faint.
	float n = flowNoise(vec3(P.x * 0.5, P.y * 0.28, 0.0), vec3(0.0, 1.4, 0.0), 6);
	float veil = spow(1.0 - abs(2.0 * n - 1.0), 5.0);
	c += veil * vec3(0.018, 0.01, 0.032) * (0.6 + 0.4 * smoothstep(-8.0, 4.0, P.y));
	// The core: a soft orb that breathes twice a loop.
	float breathe = 0.85 + 0.15 * sin(TAU * uPhase * 2.0);
	c += (exp(-r * 2.4) * 1.3 + exp(-r * 0.9) * 0.22) * mix(GLOW, HOT, exp(-r * 2.0)) * breathe;
	c += motes(P, 1.3, 1.0, 0.12, 0.045) * GLOW * 0.5;
	return c;
}

vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo) {
	vec3 ro = vec3(0.0, 0.0, 40.0);
	vec3 rd = normalize(vec3(P, 0.0) - ro);
	return col + trails(ro, rd);
}
