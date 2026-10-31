// Witching Hour - a huge orange moon behind a crooked dead tree; bats flap across it, each crossing once a loop.

const vec3 GLOW = vec3(2.4, 0.95, 0.2);
const vec3 HOT = vec3(2.8, 1.9, 0.9);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.02, 0.008, 0.004);
const vec3 AMB1 = vec3(0.01, 0.006, 0.012);
const vec3 FABRIC = vec3(0.016, 0.008, 0.012);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const vec2 MOON = vec2(0.6, 2.6);
const float MOON_R = 4.0;

float capsule(vec2 p, vec2 a, vec2 b, float ra, float rb) {
	vec2 pa = p - a, ba = b - a;
	float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
	return length(pa - ba * h) - mix(ra, rb, h);
}

// The dead tree: a leaning trunk from the bottom left, gnarled branches reaching across the moon.
float tree(vec2 P) {
	vec2 w = P + vec2(fbm3(vec3(P * 1.3, 1.0), 3) - 0.5, fbm3(vec3(P * 1.3, 4.0), 3) - 0.5) * 0.35;
	float d = capsule(w, vec2(-4.2, -8.5), vec2(-2.8, -1.0), 0.75, 0.42);
	d = min(d, capsule(w, vec2(-2.8, -1.0), vec2(-1.6, 3.0), 0.42, 0.22));
	d = min(d, capsule(w, vec2(-1.6, 3.0), vec2(-0.6, 6.2), 0.22, 0.06));
	d = min(d, capsule(w, vec2(-2.8, -1.0), vec2(1.2, 0.6), 0.3, 0.1));
	d = min(d, capsule(w, vec2(1.2, 0.6), vec2(3.6, 2.6), 0.1, 0.04));
	d = min(d, capsule(w, vec2(1.2, 0.6), vec2(2.6, -0.8), 0.08, 0.03));
	d = min(d, capsule(w, vec2(-1.6, 3.0), vec2(1.6, 4.6), 0.18, 0.05));
	d = min(d, capsule(w, vec2(1.6, 4.6), vec2(3.0, 6.4), 0.05, 0.02));
	d = min(d, capsule(w, vec2(-0.2, 3.8), vec2(-0.4, 5.0), 0.07, 0.02));
	d = min(d, capsule(w, vec2(-2.2, 1.0), vec2(-4.6, 3.0), 0.16, 0.04));
	d = min(d, capsule(w, vec2(-4.6, 3.0), vec2(-5.2, 4.8), 0.05, 0.02));
	// Ground.
	d = min(d, P.y + 7.2 - 0.4 * sin(P.x * 0.6));
	return d;
}

// A bat: a small body and two scalloped wings that rise and fall with `flap` (-1..1).
float bat(vec2 q, float flap) {
	float body = length(q * vec2(1.6, 1.0)) - 0.16;
	float ears = min(length(q - vec2(-0.07, 0.16)) - 0.05, length(q - vec2(0.07, 0.16)) - 0.05);
	vec2 w = vec2(abs(q.x), q.y);
	float lift = flap * 0.45;
	// Wing: a band from the body out to the tip, its lower edge cut into three scallops.
	float top = w.y - (0.05 + lift * w.x * 1.2 - w.x * 0.15);
	float bottom = -(w.y - (-0.12 + lift * w.x * 1.2 - 0.05 * w.x) + 0.1 * abs(sin(w.x * 7.5)));
	float wing = max(max(top, bottom), w.x - 0.95);
	return min(min(body, ears), wing);
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = mix(vec3(0.03, 0.01, 0.012), vec3(0.004, 0.002, 0.008), smoothstep(-6.0, 8.0, P.y));
	c += sparkles(P, 1.5, 0.0, 0.04) * vec3(0.9, 0.8, 0.8) * 0.25;
	float mr = length(P - MOON);
	c += exp(-max(mr - MOON_R, 0.0) * 0.6) * GLOW * 0.08 + exp(-max(mr - MOON_R, 0.0) * 3.0) * GLOW * 0.12;
	float disc = 1.0 - smoothstep(MOON_R - 0.04, MOON_R, mr);
	float craters = fbm3(vec3((P - MOON) * 0.9, 2.0), 6);
	vec3 moonCol = mix(vec3(1.5, 0.55, 0.12), vec3(2.2, 1.2, 0.45), smoothstep(MOON_R, 0.0, mr)) * (0.75 + 0.4 * craters);
	c = mix(c, moonCol * 0.6, disc);
	// Bats: six, each crossing once a loop on its own height and wobble, wings beating a whole number of times.
	float bats = 1e9;
	for (int i = 0; i < 6; i++) {
		float h = hash11(float(i) * 4.7);
		float t = fract(uPhase + float(i) / 6.0);
		float dir = mod(float(i), 2.0) < 0.5 ? 1.0 : -1.0;
		vec2 pos = vec2(dir * mix(-7.5, 7.5, t), -1.0 + h * 8.0 + 0.6 * sin(TAU * (t * 2.0 + h)));
		float size = 0.7 + 0.6 * h;
		float flap = sin(TAU * (uPhase * float(6 + i % 3 * 2) + h));
		vec2 q = (P - pos) / size;
		q.x *= dir;
		bats = min(bats, bat(q, flap) * size);
	}
	float tr = tree(P);
	float sil = min(tr, bats);
	c = mix(c, vec3(0.003, 0.0015, 0.002), 1.0 - smoothstep(-0.015, 0.015, sil));
	// Moonlight rim on whatever stands in front of the moon.
	c += exp(-abs(sil) * 30.0) * disc * GLOW * 0.12;
	return c;
}
