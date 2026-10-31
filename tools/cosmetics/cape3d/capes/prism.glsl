// Prism - a white beam crosses the dark and strikes a glass prism, which splits it into a fan of spectrum colours;
// the prism rocks slowly, so the rainbow sweeps back and forth across the cape, and dust in the beams glitters.

const vec3 GLOW = vec3(1.9, 1.9, 2.1);
const vec3 HOT = vec3(2.6, 2.6, 2.7);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.004, 0.004, 0.006);
const vec3 AMB1 = vec3(0.05, 0.05, 0.06);
const vec3 FABRIC = vec3(0.008, 0.008, 0.011);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO
#define OVERLAY

#include "kit.glsl"

const vec2 PC = vec2(-1.0, 2.6);
const float SIDE = 4.4;

float rock() { return 0.22 * sin(TAU * uPhase); }

vec2 rot2(vec2 v, float a) { return vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a)); }

// The triangle's corners on screen: apex up, turned by the rock.
vec2 corner(int i) {
	float a = PI * 0.5 + TAU * float(i) / 3.0 + rock();
	return PC + vec2(cos(a), sin(a)) * SIDE / 1.7320508;
}

float sdTri(vec2 p, vec2 a, vec2 b, vec2 c) {
	vec2 e0 = b - a, e1 = c - b, e2 = a - c;
	vec2 v0 = p - a, v1 = p - b, v2 = p - c;
	vec2 pq0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);
	vec2 pq1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);
	vec2 pq2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);
	float s = sign(e0.x * e2.y - e0.y * e2.x);
	vec2 d = min(min(vec2(dot(pq0, pq0), s * (v0.x * e0.y - v0.y * e0.x)), vec2(dot(pq1, pq1), s * (v1.x * e1.y - v1.y * e1.x))),
		vec2(dot(pq2, pq2), s * (v2.x * e2.y - v2.y * e2.x)));
	return -sqrt(d.x) * sign(d.y);
}

// The prism in 3D: the triangle extruded, its edges chamfered a little.
float extraD(vec3 p) {
	float d2 = sdTri(p.xy, corner(0), corner(1), corner(2));
	vec2 w = vec2(d2 + 0.06, abs(p.z) - 0.9);
	return min(max(w.x, w.y), 0.0) + length(max(w, 0.0)) - 0.06;
}

vec3 spectrum(float t) {
	t = clamp(t, 0.0, 1.0);
	vec3 c = 0.5 + 0.5 * cos(TAU * (vec3(t * 0.85) + vec3(0.0, 0.33, 0.67)));
	return pow(c, vec3(1.6)) * 1.6;
}

vec3 shadeExtra(vec3 p, vec3 n, vec3 v) {
	// Glass: almost clear and dark, bright on its edges and wherever it mirrors the studio; faint fire inside.
	float F = fresnel(n, v, 0.06);
	vec3 col = vec3(0.01, 0.012, 0.016) + env(reflect(-v, n)) * F * 1.6;
	float d2 = sdTri(p.xy, corner(0), corner(1), corner(2));
	col += exp(d2 * 18.0) * vec3(0.5, 0.55, 0.6);
	col += spow(abs(n.z), 0.5) * spectrum(fract(p.y * 0.25 + p.x * 0.1 + uPhase)) * 0.012;
	return col;
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = mix(vec3(0.008, 0.008, 0.012), vec3(0.002, 0.002, 0.003), smoothstep(0.0, 10.0, length(P - PC)));
	return c;
}

vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo) {
	vec2 a = corner(0), bl = corner(1), br = corner(2);
	vec2 entry = mix(a, bl, 0.55);
	vec2 exitP = mix(a, br, 0.5);
	float inside = 1.0 - step(0.0, sdTri(P, a, bl, br));
	// The white beam, coming in from the top left edge of the cape.
	vec2 src = vec2(-6.0, entry.y + 3.2);
	vec2 bd = normalize(entry - src);
	float along = dot(P - src, bd);
	float across = abs(dot(P - src, vec2(-bd.y, bd.x)));
	float beam = step(0.0, along) * step(along, length(entry - src)) * (exp(-across * across / 0.003) + exp(-across * 6.0) * 0.1);
	float dust = sparkles(P, 3.0, 0.5, 0.25) * exp(-across * 5.0) * step(0.0, along) * step(along, length(entry - src));
	col += (beam * HOT * 0.9 + dust * HOT * 0.8) * (0.9 + 0.1 * sin(TAU * uPhase * 4.0));
	// Inside the glass the light bends to the exit face.
	vec2 id = normalize(exitP - entry);
	float ia = dot(P - entry, id);
	float ic = abs(dot(P - entry, vec2(-id.y, id.x)));
	col += inside * step(0.0, ia) * step(ia, length(exitP - entry)) * exp(-ic * ic / (0.004 + max(ia, 0.0) * 0.004)) * HOT * 0.35;
	// The fan: leaving the exit face, spreading down-right, sweeping with the rock.
	vec2 e = normalize(br - a);
	vec2 fn = vec2(-e.y, e.x);   // the exit face's outward normal (to the right)
	float centerA = atan(fn.y, fn.x) - 1.6 + rock() * 1.6;
	vec2 q = P - exitP;
	float r = length(q);
	float ang = atan(q.y, q.x);
	float spread = 0.42 + 0.06 * sin(TAU * uPhase);
	float t = (ang - (centerA - spread * 0.5)) / spread;
	float inFan = step(0.0, t) * step(t, 1.0) * (1.0 - inside) * smoothstep(0.0, 0.4, r);
	float edgeSoft = smoothstep(0.0, 0.06, t) * smoothstep(1.0, 0.94, t);
	vec3 fan = spectrum(t) * inFan * edgeSoft * (0.55 + 0.45 * exp(-r * 0.08));
	col += fan * 0.55;
	col += sparkles(P, 3.0, 0.5, 0.25) * inFan * spectrum(t) * 0.5;
	// Where the beam hits and leaves: little flares.
	col += exp(-length(P - entry) * 7.0) * HOT * 0.9 + exp(-length(P - exitP) * 6.0) * HOT * 0.5;
	return col;
}
