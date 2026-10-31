// Bass Drop - a vinyl record spinning on a deck in 3D, light sliding round its grooves; four beats a loop pulse rings
// of sound out across the cape and kick the EQ bars below, and on the fourth beat the drop hits - a big shockwave and
// a flash of colour.

const vec3 GLOW = vec3(1.9, 0.4, 2.2);
const vec3 HOT = vec3(2.4, 2.2, 2.6);
const vec3 CYAN = vec3(0.3, 1.8, 2.4);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.004, 0.003, 0.006);
const vec3 AMB1 = vec3(0.05, 0.04, 0.06);
const vec3 FABRIC = vec3(0.01, 0.006, 0.014);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

const vec3 DISC = vec3(0.0, 2.0, 0.0);
const float DISC_R = 4.2;

// The record lies tipped back towards you; it turns half a turn per loop (its label repeats every half turn).
mat3 discFrame() { return rotX(-0.55); }
float spin() { return PI * uPhase; }

// Beats: four per loop. beat() = time since the last one (0..1 of a beat), drop() = 1 on the fourth.
float beatT() { return fract(uPhase * 4.0); }
float beatIndex() { return floor(uPhase * 4.0); }
float kick() { return exp(-beatT() * 7.0); }
float dropHit() { return beatIndex() > 2.5 ? exp(-beatT() * 3.5) : 0.0; }

float extraD(vec3 p) {
	vec3 q = discFrame() * (p - DISC);
	float disc = max(length(q.xy) - DISC_R, abs(q.z) - 0.06);
	return disc;
}

vec3 shadeExtra(vec3 p, vec3 n, vec3 v) {
	vec3 q = discFrame() * (p - DISC);
	float r = length(q.xy);
	float a = atan(q.y, q.x) - spin();
	vec3 col;
	if (r < 1.45) {
		// The label: two-tone halves (so a half turn looks the same), a spindle in the middle.
		float half1 = step(0.0, sin(a)) ;
		vec3 lab = mix(vec3(0.6, 0.06, 0.5), vec3(0.08, 0.5, 0.65), half1);
		lab *= 0.85 + 0.15 * sin(r * 40.0);
		lab = mix(lab, vec3(0.9, 0.85, 0.8), (1.0 - smoothstep(0.0, 0.04, abs(r - 1.2))) * 0.6);
		col = lab * (0.5 + 0.5 * max(dot(n, keyDir()), 0.0)) + vec3(0.04) * kick();
		col = mix(col, vec3(0.6), 1.0 - smoothstep(0.08, 0.1, r));
	} else {
		// Vinyl: black, fine grooves; the light catches them in two arcs that stay put while the record turns.
		float groove = 0.5 + 0.5 * sin(r * 160.0);
		float screenA = atan(q.y, q.x);
		float arc = spow(abs(cos(screenA - 0.6)), 24.0);
		col = vec3(0.012, 0.012, 0.014) * (0.7 + 0.3 * groove);
		col += arc * vec3(0.25, 0.25, 0.28) * (0.5 + 0.5 * groove) * smoothstep(1.5, 1.7, r);
		// A rainbow sheen in the arcs, as on real vinyl.
		col += arc * (0.5 + 0.5 * cos(TAU * (r * 0.6 + vec3(0.0, 0.33, 0.67)))) * 0.06;
		// Dead wax ring and the lead-out.
		col *= 1.0 - (1.0 - smoothstep(0.0, 0.05, abs(r - 1.6))) * 0.5;
		col += env(reflect(-v, n)) * fresnel(n, v, 0.04) * 0.5;
	}
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	float k = kick();
	float drop = dropHit();
	vec3 c = mix(vec3(0.01, 0.006, 0.014), vec3(0.002, 0.001, 0.003), smoothstep(0.0, 10.0, length(P - DISC.xy)));
	// Sound rings: one per beat, racing out from the record; the drop's ring is big and bright.
	float r = length((P - DISC.xy) * vec2(1.0, 1.9));
	float bt = beatT();
	float ring = exp(-pow2((r - (2.5 + bt * 10.0)) * 3.0)) * exp(-bt * 2.5);
	vec3 rc = mix(CYAN, GLOW, beatIndex() / 3.0);
	c += ring * rc * (0.35 + 1.4 * drop);
	c += exp(-pow2((r - (2.5 + bt * 6.0)) * 2.0)) * exp(-bt * 3.0) * rc * 0.12;
	// The flash on the drop.
	c += drop * exp(-r * 0.25) * mix(GLOW, CYAN, 0.5) * 0.25;
	// The deck: a dark slab under the record with its glow.
	c += exp(-length((P - DISC.xy) * vec2(0.3, 0.9))) * GLOW * 0.04 * (1.0 + 2.0 * k);
	// EQ bars along the bottom, kicked by every beat, wild on the drop.
	float bars = 18.0;
	float bx = (P.x + 5.0) / 10.0 * bars;
	float bi = floor(bx);
	float bf = fract(bx);
	float h = 0.4 + 0.5 * hash11(bi) + 1.8 * k * (0.4 + 0.6 * hash11(bi + 7.0 + beatIndex())) + 2.5 * drop * hash11(bi * 3.0 + 1.0);
	h *= 0.8 + 0.2 * sin(TAU * uPhase * 8.0 + bi);
	float bottom = -7.6;
	float inBar = step(0.15, bf) * step(bf, 0.85) * step(bottom, P.y) * step(P.y, bottom + h);
	float seg = step(0.25, fract((P.y - bottom) * 3.0));
	vec3 barC = mix(CYAN, GLOW, clamp((P.y - bottom) / 3.5, 0.0, 1.0));
	c += inBar * seg * barC * 0.6;
	c += step(0.15, bf) * step(bf, 0.85) * exp(-pow2((P.y - bottom - h) * 10.0)) * barC * 0.4;
	return c;
}
