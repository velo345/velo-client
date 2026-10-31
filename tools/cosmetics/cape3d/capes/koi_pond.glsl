// Koi Pond - looking down into dark water: two koi chase each other round in a slow yin-yang, tails beating, their
// shadows gliding on the pond floor; light ripples over the bottom, rings spread where they break the surface, lily
// pads turn and petals drift.

const vec3 GLOW = vec3(2.2, 0.8, 0.3);
const vec3 HOT = vec3(2.5, 2.3, 2.0);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.004, 0.01, 0.01);
const vec3 AMB1 = vec3(0.02, 0.04, 0.04);
const vec3 FABRIC = vec3(0.005, 0.012, 0.012);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const vec2 POND = vec2(0.0, 0.6);
const vec2 PATH = vec2(2.6, 4.1);   // the ellipse they swim

// Koi k at this pixel: (body mask, lateral -1..1, along 0..1, pattern seed). Head leads at angle th.
vec4 koi(vec2 P, int k, out float shade) {
	float th = TAU * uPhase + float(k) * PI;
	vec2 q = (P - POND) / PATH;
	float a = atan(q.y, q.x);
	float r = length(q);
	float behind = mod(th - a, TAU);            // how far behind the head (in radians)
	float u = behind * 3.3;                     // along the body, cape units
	const float L = 3.4;
	float sway = sin(u * 2.4 - TAU * uPhase * 4.0) * 0.12 * u / L;
	float v = (r - 1.0) * 3.3 + sway;
	float t = u / L;
	float width = 0.62 * sin(clamp(t, 0.0, 1.0) * PI * 0.92 + 0.12) * (1.0 - 0.3 * t);
	float body = step(u, L) * (1.0 - smoothstep(width - 0.05, width, abs(v)));
	// Tail fin: flares out past the body and beats.
	float tu = u - L;
	float tailW = 0.12 + tu * 0.7;
	float tail = step(0.0, tu) * step(tu, 1.0) * (1.0 - smoothstep(tailW - 0.06, tailW, abs(v + sin(TAU * uPhase * 4.0) * 0.2 * tu)));
	// Pectoral fins near the head.
	float fin = (1.0 - smoothstep(0.0, 0.25, abs(u - 0.7))) * (1.0 - smoothstep(width, width + 0.35, abs(v))) * step(width - 0.05, abs(v)) * 0.6;
	shade = clamp(1.0 - pow2(v / max(width, 0.05)), 0.0, 1.0);
	return vec4(max(max(body, tail * 0.75), fin), v / max(width, 0.05), t, float(k));
}

vec3 capeBackground(vec2 P, float halo) {
	// Pond floor: dark teal, caustics rippling over it (a looping flow of Voronoi edges).
	vec2 f = P * 0.9;
	float a0 = fract(uPhase), a1 = fract(uPhase + 0.5);
	vec2 v0 = voronoi3(vec3(f + vec2(0.4, 0.9) * a0, 0.0)).xy;
	vec2 v1 = voronoi3(vec3(f + vec2(0.4, 0.9) * a1 + 5.0, 1.0)).xy;
	float ca = 1.0 - smoothstep(0.0, 0.08, v0.y - v0.x);
	float cb = 1.0 - smoothstep(0.0, 0.08, v1.y - v1.x);
	float caustic = mix(ca, cb, abs(1.0 - 2.0 * a0));
	vec3 c = mix(vec3(0.006, 0.02, 0.022), vec3(0.002, 0.006, 0.008), smoothstep(0.0, 9.0, length(P - POND)));
	c += caustic * vec3(0.012, 0.03, 0.028);
	// Shadows of the fish on the floor (offset down and right), then the fish.
	for (int k = 0; k < 2; k++) {
		float sh;
		vec4 s = koi(P - vec2(0.35, -0.45), k, sh);
		c *= 1.0 - s.x * 0.6;
	}
	for (int k = 0; k < 2; k++) {
		float sh;
		vec4 f4 = koi(P, k, sh);
		if (f4.x > 0.01) {
			// Kohaku (white with red) and showa (black, red and white): patches from noise along the body.
			float patch1 = fbm3(vec3(f4.z * 5.0, f4.y * 1.2, float(k) * 9.0), 4);
			vec3 baseC = k == 0 ? vec3(0.85, 0.82, 0.78) : vec3(0.05, 0.045, 0.045);
			vec3 spotC = k == 0 ? vec3(0.95, 0.22, 0.05) : vec3(0.9, 0.2, 0.04);
			vec3 fish = mix(baseC, spotC, smoothstep(0.45, 0.6, patch1));
			if (k == 1) fish = mix(fish, vec3(0.85, 0.8, 0.75), smoothstep(0.62, 0.72, fbm3(vec3(f4.z * 7.0, f4.y, 3.0), 3)));
			// Rounded body: lit along the spine, scales catching a glint.
			fish *= 0.35 + 0.75 * sh;
			fish += spow(sh, 12.0) * vec3(0.25) * (0.6 + 0.4 * sin(f4.z * 60.0));
			c = mix(c, fish * 0.9, f4.x);
		}
	}
	// Rings where they broke the surface: three, each spreading once a loop at its own time.
	for (int i = 0; i < 3; i++) {
		vec2 src = POND + vec2(cos(float(i) * 2.1 + 0.5) * 3.0, sin(float(i) * 2.1 + 0.5) * 4.5);
		float age = fract(uPhase + float(i) / 3.0);
		float rr = length(P - src);
		float ring = exp(-pow2((rr - age * 3.5) * 10.0)) + exp(-pow2((rr - age * 2.4) * 12.0)) * 0.6;
		c += ring * (1.0 - age) * vec3(0.12, 0.18, 0.18);
	}
	// Lily pads: dark green discs with a notch, turning slowly.
	vec3 pads[3] = vec3[3](vec3(-3.4, 6.2, 1.1), vec3(3.6, -5.6, 1.3), vec3(-3.8, -6.6, 0.8));
	for (int i = 0; i < 3; i++) {
		vec2 pq = P - pads[i].xy;
		float pr = length(pq);
		float pa = atan(pq.y, pq.x) + TAU * uPhase * (i == 1 ? -0.25 : 0.25) + float(i);
		float notch = step(abs(mod(pa, TAU) - 0.3), 0.3);
		float pad = (1.0 - smoothstep(pads[i].z - 0.03, pads[i].z, pr)) * (1.0 - notch);
		float vein = 1.0 - smoothstep(0.0, 0.04, abs(fract(pa / TAU * 14.0) - 0.5) * pr);
		vec3 padC = vec3(0.025, 0.09, 0.035) * (0.85 + 0.15 * fbm3(vec3(pq * 4.0, 1.0), 3)) + vein * vec3(0.01, 0.03, 0.012) + vec3(0.015, 0.04, 0.015) * (1.0 - pr / pads[i].z);
		c = mix(c * (1.0 - 0.4 * exp(-max(pr - pads[i].z, 0.0) * 4.0)), padC, pad);
	}
	// Petals drifting: a few pink ovals on a slow circle round the pond.
	for (int i = 0; i < 6; i++) {
		float pa = TAU * (uPhase + float(i) / 6.0);
		vec2 pp = POND + vec2(cos(pa) * 3.9, sin(pa) * 6.2) + vec2(sin(float(i) * 3.0), cos(float(i) * 2.0)) * 0.6;
		vec2 dq = P - pp;
		float rot = pa * 2.0 + float(i);
		dq = vec2(dq.x * cos(rot) - dq.y * sin(rot), dq.x * sin(rot) + dq.y * cos(rot));
		float petal = 1.0 - smoothstep(0.9, 1.0, length(dq * vec2(5.5, 9.0)));
		c = mix(c, vec3(0.9, 0.45, 0.55), petal * 0.9);
	}
	return c;
}
