// Gift Wrap - red foil paper printed with silver snowflakes that glint in turn, crossed by gold satin ribbon; a 3D
// satin bow on top that, once a loop, pops - it springs up and bursts gold confetti and sparks.

const vec3 GLOW = vec3(2.4, 1.6, 0.5);
const vec3 HOT = vec3(2.7, 2.3, 1.5);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.03, 0.01, 0.01);
const vec3 AMB1 = vec3(0.2, 0.16, 0.12);
const vec3 FABRIC = vec3(0.03, 0.005, 0.007);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO
#define OVERLAY

#include "kit.glsl"

const vec3 KNOT = vec3(0.0, 2.2, 0.5);
const float POP_AT = 0.5;

float popAge() { return fract(uPhase - POP_AT); }
// The spring of the pop: a quick overshoot that settles.
float popScale() {
	float a = popAge();
	return 1.65 * (1.0 + 0.22 * exp(-a * 9.0) * sin(a * 40.0));
}

mat3 bowRot() { return rotZ(0.05 * sin(TAU * uPhase)); }

float sdBox3(vec3 p, vec3 b, float r) { vec3 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r; }

float extraD(vec3 p) {
	vec3 q = bowRot() * (p - KNOT) / popScale();
	float d = sdBox3(q, vec3(0.32, 0.36, 0.22), 0.12);
	// Two loops: flattened rings tipped out to each side.
	for (int i = 0; i < 2; i++) {
		float side = i == 0 ? -1.0 : 1.0;
		vec3 l = q - vec3(side * 1.05, 0.25, 0.0);
		l = rotZ(side * 0.35) * l;
		vec2 ring = vec2(length(l.xy * vec2(1.0, 1.6)) - 0.85, l.z);
		d = min(d, (length(ring * vec2(1.0, 0.5)) - 0.12) * 0.7);
		// Tails: flat strips hanging down and out, cut in a V.
		vec3 t = q - vec3(side * 0.5, -1.0, 0.05);
		t = rotZ(side * 0.4) * t;
		float tail = sdBox3(t, vec3(0.34, 0.95, 0.04), 0.03);
		tail = max(tail, -(t.y + 0.95 - abs(t.x) * 0.9));
		d = min(d, tail);
	}
	return d * popScale();
}

vec3 satin(vec3 n, vec3 v, float across) {
	vec3 gold = vec3(0.85, 0.52, 0.12);
	vec3 L = keyDir();
	// Satin: a broad soft sheen that slides across with the light, on top of the gold.
	float sheen = spow(0.5 + 0.5 * cos(across * 5.0 - TAU * uPhase), 6.0);
	vec3 col = gold * (0.12 + 0.6 * max(dot(n, L), 0.0)) + env(reflect(-v, n)) * gold * 0.5;
	col += sheen * gold * 0.6 + spow(dot(n, normalize(L + v)), 40.0) * HOT * 0.5;
	return col;
}

vec3 shadeExtra(vec3 p, vec3 n, vec3 v) {
	vec3 q = bowRot() * (p - KNOT);
	return satin(n, v, q.x * 0.8 + q.y * 0.6);
}

float snowflake(vec2 q) {
	float r = length(q);
	float a = atan(q.y, q.x);
	float arm = abs(mod(a + PI / 6.0, PI / 3.0) - PI / 6.0) * r;
	float d = max(arm - 0.03, r - 0.42);
	// Side branches on each arm.
	vec2 rq = vec2(r * cos(mod(a + PI / 6.0, PI / 3.0) - PI / 6.0), arm);
	float br = abs(rq.y - (rq.x - 0.22) * 0.8) - 0.025;
	br = max(br, max(rq.x - 0.34, 0.2 - rq.x));
	return min(d, br);
}

vec3 capeBackground(vec2 P, float halo) {
	// Red foil: deep red with a fine crinkle catching the light.
	float cr = fbm3(vec3(P * 3.0, 0.0), 4);
	vec3 c = mix(vec3(0.14, 0.008, 0.015), vec3(0.32, 0.02, 0.03), cr) * (0.8 + 0.4 * smoothstep(9.0, 0.0, length(P - vec2(-2.0, 4.0))));
	c += spow(cr, 8.0) * vec3(0.4, 0.1, 0.1) * 0.6;
	// Silver snowflakes on a staggered grid, glinting one diagonal after another.
	vec2 g = P / 2.3;
	g.x += mod(floor(g.y), 2.0) * 0.5;
	vec2 id = floor(g);
	vec2 f = (fract(g) - 0.5) * 2.3;
	float rot = hash21(id) * 1.0;
	f = vec2(f.x * cos(rot) - f.y * sin(rot), f.x * sin(rot) + f.y * cos(rot));
	float sf = snowflake(f);
	float flake = 1.0 - smoothstep(0.0, 0.02, sf);
	float glint = spow(0.5 + 0.5 * sin(TAU * uPhase * 2.0 - (id.x + id.y) * 0.9), 12.0);
	c = mix(c, vec3(0.16, 0.15, 0.17) + glint * vec3(1.1), flake * 0.85);
	// Ribbon: a vertical and a horizontal band of gold satin with thin stitched edges.
	float vb = abs(P.x) - 0.7;
	float hb = abs(P.y - KNOT.y) - 0.6;
	if (vb < 0.0 || hb < 0.0) {
		bool vert = vb < hb;
		float across = vert ? P.x / 0.7 : (P.y - KNOT.y) / 0.6;
		vec3 n = normalize(vec3(vert ? -across * 0.5 : 0.0, vert ? 0.0 : -across * 0.5, 1.0));
		c = satin(n, vec3(0.0, 0.0, 1.0), vert ? P.y * 0.25 + across : P.x * 0.25 + across);
		float edge = 1.0 - abs(across);
		c *= 0.7 + 0.3 * smoothstep(0.0, 0.12, edge);
		c += (1.0 - smoothstep(0.0, 0.02, abs(edge - 0.08))) * step(0.5, fract((vert ? P.y : P.x) * 5.0)) * vec3(0.3, 0.2, 0.05);
	}
	// The bow's shadow on the paper.
	c *= 1.0 - 0.5 * exp(-length((P - KNOT.xy - vec2(0.2, -0.4)) * vec2(0.3, 0.4)) * 1.4);
	return c;
}

vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo) {
	// The pop: confetti and sparks thrown out of the knot, falling and fading.
	float a = popAge();
	if (a > 0.6) return col;
	float fade = 1.0 - smoothstep(0.25, 0.6, a);
	for (int i = 0; i < 46; i++) {
		float h = hash11(float(i) * 7.31);
		float h2 = hash11(float(i) * 3.17 + 1.0);
		float ang = TAU * h;
		float speed = 6.0 + 7.0 * h2;
		vec2 pos = KNOT.xy + vec2(cos(ang), sin(ang) * 0.85 + 0.35) * speed * a - vec2(0.0, 14.0 * a * a);
		vec2 q = P - pos;
		float spin = TAU * (a * (2.0 + 3.0 * h)) + h * 6.0;
		q = vec2(q.x * cos(spin) - q.y * sin(spin), q.x * sin(spin) + q.y * cos(spin));
		float piece = 1.0 - smoothstep(0.0, 0.025, max(abs(q.x) - 0.12, abs(q.y) - 0.05 * abs(cos(spin * 1.3))));
		vec3 pc = h2 < 0.45 ? vec3(1.6, 1.15, 0.35) : h2 < 0.75 ? vec3(1.1, 1.1, 1.15) : vec3(1.4, 0.1, 0.12);
		col = mix(col, pc, piece * fade);
	}
	// Sparks right at the burst.
	col += exp(-length(P - KNOT.xy) * (2.5 + a * 6.0)) * HOT * exp(-a * 8.0) * 1.5;
	col += sparkles(P - KNOT.xy, 1.8, 0.0, 0.3) * exp(-length(P - KNOT.xy) * 0.4) * HOT * exp(-a * 5.0);
	return col;
}
