// Dragon Eye - one huge reptile eye set in dark scales: a burning amber iris with a slit pupil that narrows and
// widens, the gaze flicking left and right, a wet glint on the cornea, and once a loop the scaly lids snap shut.

const vec3 GLOW = vec3(2.4, 1.1, 0.2);
const vec3 HOT = vec3(2.7, 2.2, 1.2);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.004, 0.006, 0.004);
const vec3 AMB1 = vec3(0.03, 0.04, 0.03);
const vec3 FABRIC = vec3(0.006, 0.01, 0.007);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const vec2 EYE = vec2(0.0, 1.6);
// For the in-game gaze atlas (dragon_eye_gaze.py): the gaze set from outside (-1..1 each way), eye wide open.
uniform vec2 uGaze;
uniform float uGazeOn;
const vec2 EYE_R = vec2(4.3, 2.5);
const float BLINK_AT = 0.72;

// Lid opening 0..1: open, then a quick snap shut and open again.
float openness() {
	if (uGazeOn > 0.5) return 1.0;
	float t = (uPhase - BLINK_AT) / 0.1;
	return 1.0 - exp(-pow2(t * 2.2)) * 0.98;
}

// The gaze: holds, flicks to a new spot, holds - three spots per loop.
vec2 gaze() {
	if (uGazeOn > 0.5) return uGaze * vec2(1.15, 0.7);
	float t = uPhase * 3.0;
	float i = floor(t);
	float f = smoothstep(0.75, 0.95, fract(t));
	vec2 a = (hash22(vec2(mod(i, 3.0), 1.0)) - 0.5) * vec2(1.6, 0.5);
	vec2 b = (hash22(vec2(mod(i + 1.0, 3.0), 1.0)) - 0.5) * vec2(1.6, 0.5);
	return mix(a, b, f);
}

// Dragon scales: rounded plates hanging in offset rows, each row overlapping the one below. Every plate is domed and
// shaded in 3D - a key light from the top left, the eye's glow on the edges that face it, an iridescent sheen that
// slides across once a loop - with dark crevices between.
vec3 scales(vec2 P) {
	vec2 g = P * vec2(1.25, 1.6);
	float row0 = floor(g.y);
	for (int j = 1; j >= -1; j--) {
		float row = row0 + float(j);
		float off = mod(row, 2.0) * 0.5;
		float k = floor(g.x - off + 0.5);
		for (int i = -1; i <= 1; i++) {
			vec2 c = vec2(k + float(i) + off, row + 0.15);
			vec2 f = (g - c) * vec2(1.0, 1.15);
			float r = length(f);
			// A plate is the lower part of a circle hanging from its row: pointed slightly at the bottom.
			float tip = f.y < 0.0 ? r + abs(f.x) * 0.25 * (-f.y) : r * 1.6;
			if (tip < 0.62 && f.y < 0.25) {
				float h = 1.0 - tip / 0.62;
				vec2 grad = normalize(f + 1e-4);
				vec3 n = normalize(vec3(grad * (1.0 - h) * 1.4, 0.6 + h));
				// A ridge down the middle of each plate.
				n.x += -sign(f.x) * exp(-abs(f.x) * 14.0) * 0.4 * h;
				n = normalize(n);
				vec3 L = normalize(vec3(-0.5, 0.7, 0.6));
				float hs = hash21(c);
				vec3 base = mix(vec3(0.006, 0.02, 0.012), vec3(0.02, 0.06, 0.035), hs);
				vec3 col = base * (0.25 + 1.2 * max(dot(n, L), 0.0));
				// Iridescent sheen: green to teal to violet depending on the plate's tilt, sliding across.
				float sheen = spow(max(dot(n, normalize(L + vec3(0.0, 0.0, 1.0))), 0.0), 18.0);
				vec3 irid = 0.5 + 0.5 * cos(TAU * (dot(n.xy, vec2(0.6, 0.4)) * 1.2 + uPhase + vec3(0.0, 0.25, 0.55)));
				col += sheen * mix(vec3(0.1, 0.4, 0.2), irid * vec3(0.2, 0.5, 0.4), 0.5) * 0.6;
				// The eye's glow catches the plate edges facing it.
				vec2 toEye = normalize(EYE - P);
				float near = exp(-length((P - EYE) / EYE_R) * 1.3);
				col += GLOW * 0.25 * near * spow(max(dot(n.xy, toEye), 0.0), 2.0) * (1.0 - h * 0.5);
				// Dark crevice at the rim where it tucks under its neighbours.
				col *= smoothstep(0.0, 0.12, h) * 0.85 + 0.15;
				return col;
			}
		}
	}
	return vec3(0.002, 0.004, 0.003);
}

vec3 capeBackground(vec2 P, float halo) {
	vec2 q = (P - EYE) / EYE_R;
	// Almond eye shape: two arcs meeting in points; the lids close it vertically.
	float open = openness();
	float almond = abs(q.y) - 0.98 * (1.0 - q.x * q.x) * open;
	float inEye = (1.0 - smoothstep(-0.02, 0.02, almond)) * step(abs(q.x), 1.0);
	vec3 col = scales(P);
	// Heavy brow and lid ridges round the eye, catching the eye's glow.
	float lidD = abs(almond);
	col += exp(-lidD * 10.0) * step(abs(q.x), 1.05) * GLOW * 0.08 * open;
	col *= 1.0 - 0.6 * exp(-max(almond, 0.0) * 6.0) * step(abs(q.x), 1.1);
	if (inEye > 0.0) {
		// Sclera: deep amber-brown, darker towards the corners.
		vec3 eye = mix(vec3(0.25, 0.08, 0.01), vec3(0.04, 0.01, 0.0), smoothstep(0.3, 1.0, length(q)));
		vec2 ic = (P - EYE - gaze()) / 2.0;
		float ir = length(ic);
		float ia = atan(ic.y, ic.x);
		// Iris: radial fibres of fire, brighter round the pupil, a dark limbal ring.
		float fib = fbm3(vec3(cos(ia) * 6.0, sin(ia) * 6.0, ir * 5.0), 5);
		float fib2 = fbm3(vec3(cos(ia) * 18.0, sin(ia) * 18.0, ir * 9.0), 3);
		vec3 iris = mix(vec3(1.9, 0.45, 0.04), vec3(2.6, 1.8, 0.5), smoothstep(0.8, 0.15, ir) * fib);
		iris *= 0.55 + 0.6 * fib2;
		iris *= 1.0 - smoothstep(0.75, 1.0, ir) * 0.85;
		float inIris = 1.0 - smoothstep(0.98, 1.02, ir);
		eye = mix(eye, iris, inIris);
		// Slit pupil: narrows and widens twice a loop, wider during the blink.
		float pw = 0.09 + 0.08 * (0.5 + 0.5 * sin(TAU * uPhase * 2.0)) + 0.15 * (1.0 - open);
		float pupil = abs(ic.x) - pw * (1.0 - ic.y * ic.y / 0.85);
		eye = mix(eye, vec3(0.0), (1.0 - smoothstep(-0.015, 0.015, pupil)) * step(abs(ic.y), 0.93));
		eye += exp(-abs(pupil) * 30.0) * step(abs(ic.y), 0.9) * GLOW * 0.25;
		// Cornea: a wet bulge - a soft highlight and a sharp window glint that doesn't move with the iris.
		vec2 g = P - EYE - vec2(-1.2, 0.9);
		eye += exp(-dot(g, g) * 9.0) * vec3(1.6) + exp(-length(q - vec2(0.15, 0.45)) * 3.0) * vec3(0.08);
		col = mix(col, eye, inEye);
	}
	// The glow of the eye on the scales.
	col += exp(-length((P - EYE) / EYE_R) * 1.5) * GLOW * 0.03 * open;
	return col;
}
