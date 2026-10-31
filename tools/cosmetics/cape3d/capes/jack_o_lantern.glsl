// Jack o'Lantern - a carved pumpkin in 3D, ribbed and glossy. Once a loop it cackles: it bounces and squashes three
// times, flames roar out of its eyes and grin, embers spray from its mouth and two beams of light sweep from its
// eyes. Between laughs the candle flickers and a thread of smoke curls up from its lid.

const vec3 GLOW = vec3(2.4, 0.95, 0.15);
const vec3 HOT = vec3(2.8, 2.0, 0.8);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.03, 0.012, 0.004);
const vec3 AMB1 = vec3(0.012, 0.009, 0.01);
const vec3 FABRIC = vec3(0.02, 0.01, 0.005);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO
#define OVERLAY

#include "kit.glsl"

const vec3 PUMPKIN = vec3(0.0, -0.4, 0.0);

mat3 pumpkinRot() { return rotY(0.22 * sin(TAU * uPhase)); }

// The cackle: 0 outside 0.2..0.7 of the loop, three bounces inside it.
float laughWindow() { return smoothstep(0.18, 0.26, uPhase) * (1.0 - smoothstep(0.62, 0.72, uPhase)); }
float bounce() { return abs(sin(PI * 3.0 * clamp((uPhase - 0.2) / 0.5, 0.0, 1.0))) * laughWindow(); }
float laugh() { return laughWindow() * (0.55 + 0.45 * bounce()); }

// Pumpkin space with the bounce: it hops up and squashes as it lands.
vec3 toPumpkin(vec3 p) {
	float b = bounce();
	vec3 q = pumpkinRot() * (p - PUMPKIN - vec3(0.0, 0.7 * b, 0.0));
	float squash = 1.0 - 0.1 * (1.0 - b) * laughWindow();
	return q / vec3(1.0 / sqrt(squash), squash, 1.0 / sqrt(squash));
}

// Candlelight: an irregular flicker made of whole-number frequencies so it loops.
float candle() {
	float t = TAU * uPhase;
	return 0.78 + 0.12 * sin(t * 7.0) * sin(t * 3.0 + 1.0) + 0.1 * sin(t * 13.0 + 2.0) + laugh() * 0.9;
}

float extraD(vec3 p) {
	vec3 q = toPumpkin(p);
	float ang = atan(q.z, q.x);
	// Eight lobes: the radius dips into a groove between each.
	float lobes = 1.0 - 0.07 * spow(abs(sin(ang * 4.0)), 0.6);
	float body = (length(q * vec3(1.0, 1.25, 1.0)) - 3.3 * lobes) * 0.75;
	// The stem: a short, slightly bent cylinder on top.
	vec3 s = q - vec3(0.15 * (q.y - 2.6), 2.75, 0.0);
	float stem = max(length(s.xz) - 0.28 + 0.05 * s.y, abs(s.y) - 0.55);
	return min(body, stem) * 0.85;
}

// The carved face in pumpkin space (x right, y up, looking at its front): negative inside a cut.
float face(vec2 q) {
	// Eyes: triangles.
	float eye = 1e9;
	for (int i = 0; i < 2; i++) {
		vec2 e = q - vec2(i == 0 ? -1.15 : 1.15, 0.75);
		e.x *= i == 0 ? 1.0 : -1.0;
		float tri = max(max(-e.y - 0.45, e.y * 0.9 + e.x * 1.1 - 0.35), e.y * 0.9 - e.x * 1.4 - 0.35);
		eye = min(eye, tri);
	}
	vec2 n = q - vec2(0.0, 0.0);
	float nose = max(max(-n.y - 0.22, n.y + abs(n.x) * 1.6 - 0.25), -1e9);
	// Mouth: a wide crescent with two teeth.
	vec2 m = q - vec2(0.0, -0.75);
	float smile = max(length(m * vec2(0.55, 1.0) - vec2(0.0, 0.75)) - 1.25, -(length(m * vec2(0.55, 1.0) - vec2(0.0, 1.25)) - 1.25));
	float teeth = min(length(max(abs(m - vec2(-0.55, -0.15)) - vec2(0.16, 0.2), 0.0)), length(max(abs(m - vec2(0.6, -0.3)) - vec2(0.16, 0.2), 0.0)));
	smile = max(smile, -(teeth - 0.02));
	return min(min(eye, nose), smile);
}

vec3 shadeExtra(vec3 p, vec3 n, vec3 v) {
	vec3 q = toPumpkin(p);
	vec3 ln = pumpkinRot() * n;
	vec3 L = normalize(vec3(-0.5, 0.6, 0.7));
	float flick = candle();
	if (q.y > 2.55) {
		// Stem: dry green-brown.
		return vec3(0.05, 0.045, 0.015) * (0.3 + max(dot(n, L), 0.0)) + vec3(0.02, 0.01, 0.0);
	}
	float ang = atan(q.z, q.x);
	float groove = spow(abs(sin(ang * 4.0)), 0.6);
	// Skin: deep orange, darker in the grooves, a soft sheen, lit faintly from outside and warmly from below.
	vec3 skin = mix(vec3(0.5, 0.13, 0.015), vec3(0.18, 0.04, 0.008), groove) * (0.85 + 0.3 * fbm3(q * 3.0, 3));
	vec3 col = skin * (0.12 + 0.55 * max(dot(n, L), 0.0));
	col += spow(dot(n, normalize(L + v)), 40.0) * vec3(0.35, 0.22, 0.12);
	col += skin * GLOW * 0.08 * smoothstep(0.0, -3.0, q.y);
	// The face, on the front only: the cut glows with the candle; its rim shows the lit pulp.
	if (q.z > 1.0) {
		float f = face(q.xy);
		float inside = 1.0 - smoothstep(-0.02, 0.02, f);
		float rim = (1.0 - smoothstep(0.0, 0.12, abs(f))) * (1.0 - inside);
		vec3 fire = mix(GLOW, HOT, smoothstep(0.0, -0.4, f)) * flick;
		col = mix(col, fire * (0.9 + 0.3 * smoothstep(0.0, -0.3, f)), inside);
		col += rim * vec3(1.6, 0.9, 0.3) * flick * 0.6;
		// Glow on the skin just round the cuts.
		col += exp(-max(f, 0.0) * 7.0) * GLOW * 0.1 * flick * (1.0 - inside) * smoothstep(1.2, 2.6, q.z);
	}
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	float flick = candle();
	vec3 c = mix(vec3(0.012, 0.006, 0.003), vec3(0.002, 0.0015, 0.002), smoothstep(-6.0, 6.0, P.y));
	// Its light pooled on the ground and round it in the air.
	c += exp(-length((P - vec2(0.0, -4.6)) * vec2(0.3, 1.2))) * GLOW * 0.09 * flick;
	c += exp(-length(P - PUMPKIN.xy) * 0.35) * GLOW * 0.05 * flick;
	// The ground line and the pumpkin's shadow on it.
	float ground = smoothstep(-4.35, -4.5, P.y);
	c *= 1.0 - ground * 0.3 * (1.0 - smoothstep(0.0, 4.0, abs(P.x)));
	// A thread of smoke rising from the lid, curling as it goes.
	float sy = P.y - 2.9;
	float sx = P.x - 0.4 * sin(sy * 0.9 - TAU * uPhase) * smoothstep(0.0, 4.0, sy) - 0.1;
	float smoke = exp(-abs(sx) * (5.0 - 3.5 * smoothstep(0.0, 6.0, sy))) * smoothstep(0.0, 0.6, sy) * smoothstep(8.0, 2.0, sy);
	smoke *= 0.5 + 0.5 * fbm3(vec3(P.x * 2.0, sy * 1.5 - uPhase * 3.0, 0.0), 4);
	c += smoke * vec3(0.06, 0.05, 0.045) * 0.8;
	c += motes(P, 1.3, 1.0, 0.08, 0.04) * GLOW * 0.3;
	return c;
}

// Pumpkin space -> world, exactly undoing toPumpkin (turn, hop and squash).
vec3 fromPumpkin(vec3 q) {
	float squash = 1.0 - 0.1 * (1.0 - bounce()) * laughWindow();
	q *= vec3(1.0 / sqrt(squash), squash, 1.0 / sqrt(squash));
	return transpose(pumpkinRot()) * q + PUMPKIN + vec3(0.0, 0.7 * bounce(), 0.0);
}

// World -> screen, the same camera the ray march uses (eye at z = 40 looking at the z = 0 plane).
vec2 toScreen(vec3 p) { return p.xy * 40.0 / (40.0 - p.z); }

// A point of the carved face (pumpkin-space x, y) where it sits on the skin, on screen.
vec3 faceWorld(vec2 local) {
	float z = sqrt(max(3.3 * 3.3 - local.x * local.x - pow2(local.y * 1.25), 0.01));
	return fromPumpkin(vec3(local, z));
}
vec2 faceAt(vec2 local) { return toScreen(faceWorld(local)); }

vec3 fireRamp(float t) {
	t = clamp(t, 0.0, 1.0);
	vec3 c = mix(vec3(0.0), vec3(0.5, 0.05, 0.0), smoothstep(0.0, 0.3, t));
	c = mix(c, vec3(2.0, 0.6, 0.06), smoothstep(0.3, 0.65, t));
	return mix(c, vec3(2.8, 1.9, 0.7), smoothstep(0.65, 0.95, t));
}

// A tongue of flame rising from `src`, `len` long.
float flame(vec2 P, vec2 src, float width, float len, float seed) {
	vec2 q = P - src;
	float n = flowNoise(vec3(q.x * 2.2, q.y * 1.2, seed), vec3(0.0, 4.0, 0.0), 4);
	float h = clamp(q.y / len, 0.0, 1.0);
	float w = width * (1.0 - h) * (0.7 + 0.6 * n);
	float body = (1.0 - smoothstep(w * 0.6, w, abs(q.x + (n - 0.5) * 0.5 * h))) * step(-0.05, q.y) * (1.0 - h);
	return body * (0.6 + 0.6 * n);
}

vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo) {
	float l = laugh();
	float flick = candle();
	// Flames out of the eyes and the grin, roaring up while it laughs.
	float f = 0.0;
	f += flame(P, faceAt(vec2(-1.15, 0.9)), 0.45, 1.0 + 2.4 * l, 1.0);
	f += flame(P, faceAt(vec2(1.15, 0.9)), 0.45, 1.0 + 2.4 * l, 2.0);
	f += flame(P, faceAt(vec2(0.0, -0.6)), 1.2, 0.6 + 2.0 * l, 3.0) * l;
	col += fireRamp(f * (0.5 + 0.8 * l)) * (0.25 + 0.75 * l);
	// Two beams from the eyes, sweeping out and up while it laughs.
	for (int i = 0; i < 2; i++) {
		float side = i == 0 ? -1.0 : 1.0;
		// The beam leaves the eye along the pumpkin's own forward-and-out direction, so it turns with the head.
		vec3 eyeW = faceWorld(vec2(side * 1.15, 0.75));
		float ang = side * (0.55 + 0.35 * sin(TAU * uPhase * 2.0 + float(i)));
		vec3 outW = transpose(pumpkinRot()) * normalize(vec3(sin(ang), cos(ang) * 0.9, 0.5));
		vec2 eye = toScreen(eyeW);
		vec2 dir = normalize(toScreen(eyeW + outW) - eye + 1e-5);
		vec2 q = P - eye;
		float along = dot(q, dir);
		float across = abs(dot(q, vec2(-dir.y, dir.x)));
		float beam = along > 0.0 ? exp(-across / (0.15 + along * 0.12)) * exp(-along * 0.18) : 0.0;
		col += beam * GLOW * 0.35 * l * flick;
	}
	// Embers sprayed from the mouth: fast rising sparks in a cone above it.
	vec2 m = P - faceAt(vec2(0.0, -0.8));
	float cone = smoothstep(0.0, 1.0, 1.0 - abs(m.x) / (0.6 + max(m.y, 0.0) * 0.7)) * step(0.0, m.y);
	col += motes(P, 2.6, 6.0, 0.35, 0.06) * cone * HOT * 1.4 * l;
	return col;
}
