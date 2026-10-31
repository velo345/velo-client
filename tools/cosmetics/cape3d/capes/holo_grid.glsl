// Holo Grid - a floor of hexagon cells running off into the dark, lighting up in slow waves from cyan to violet; above
// it a projector beam holds a turning holographic globe with an orbiting satellite, a scan line sweeping through it.

const vec3 GLOW = vec3(0.25, 1.5, 2.3);
const vec3 HOT = vec3(1.7, 2.3, 2.7);
const vec3 VIOLET = vec3(1.2, 0.4, 2.4);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.003, 0.006, 0.012);
const vec3 AMB1 = vec3(0.02, 0.04, 0.07);
const vec3 FABRIC = vec3(0.006, 0.01, 0.018);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO
#define OVERLAY

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const float HORIZON = -1.6;
const vec3 GEM = vec3(0.0, 2.6, 0.0);

// Distance to the nearest hex cell edge and the cell's id.
vec4 hexCell(vec2 p) {
	const vec2 s = vec2(1.0, 1.7320508);
	vec4 hc = floor(vec4(p, p - vec2(0.5, 1.0)) / s.xyxy) + 0.5;
	vec4 h = vec4(p - hc.xy * s, p - (hc.zw + 0.5) * s);
	vec4 r = dot(h.xy, h.xy) < dot(h.zw, h.zw) ? vec4(h.xy, hc.xy) : vec4(h.zw, hc.zw + 0.5);
	vec2 a = abs(r.xy);
	float edge = 0.5 - max(dot(a, s * 0.5), a.x);
	return vec4(edge, 0.0, r.zw);
}

vec2 raySeg(vec3 ro, vec3 rd, vec3 a, vec3 b) {
	vec3 ba = b - a, oa = ro - a;
	float baba = dot(ba, ba), bard = dot(ba, rd), baoa = dot(ba, oa), rdoa = dot(rd, oa);
	float s = clamp((baoa - rdoa * bard) / max(baba - bard * bard, 1e-6), 0.0, 1.0);
	float t = max(s * bard - rdoa, 0.0);
	return vec2(length(oa + t * rd - s * ba), t);
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = mix(vec3(0.004, 0.008, 0.02), vec3(0.001, 0.002, 0.005), smoothstep(HORIZON, 8.0, P.y));
	if (P.y < HORIZON) {
		// The floor in perspective: depth from how far below the horizon we look.
		float below = HORIZON - P.y;
		float depth = 4.0 / below;
		vec2 fp = vec2(P.x * depth * 0.28, depth * 1.4);
		vec4 hx = hexCell(fp * 1.2);
		vec2 id = hx.zw;
		float dist = length(id * vec2(1.0, 1.73) - vec2(0.0, 2.0));
		float wave = 0.5 + 0.5 * sin(TAU * uPhase - dist * 0.45);
		float lit = spow(wave, 4.0);
		vec3 tint = mix(GLOW, VIOLET, 0.5 + 0.5 * sin(TAU * uPhase + dist * 0.2));
		float line = 1.0 - smoothstep(0.0, 0.05 * (1.0 + depth * 0.15), hx.x);
		float fade = exp(-depth * 0.22) * smoothstep(0.0, 0.4, below);
		c += (line * (0.35 + 1.3 * lit) + lit * 0.08 * step(0.0, hx.x)) * tint * fade * 0.6;
		c += exp(-below * 2.5) * GLOW * 0.08;
	}
	// The projector's beam: a soft cone from the floor up to the crystal.
	vec2 bq = P - vec2(0.0, -3.2);
	float cone = smoothstep(0.0, 1.0, 1.0 - abs(bq.x) / (0.25 + max(bq.y, 0.0) * 0.48)) * smoothstep(0.0, 0.6, bq.y) * smoothstep(7.5, 4.0, bq.y);
	c += cone * GLOW * 0.07;
	c += exp(-length((P - vec2(0.0, -3.2)) * vec2(1.0, 3.0)) * 3.0) * HOT * 0.6;
	c += sparkles(P, 1.3, 0.0, 0.04) * GLOW * 0.25 * step(HORIZON, P.y);
	return c;
}

// The globe: latitude and longitude lines every 15 degrees, turning one line-spacing per loop (so it loops), front
// lines bright, the far side dim through it; data points blink on its surface; a ring orbits it with a satellite.
const float GLOBE_R = 2.35;
const float STEP = TAU / 24.0;

float gridLines(vec3 q, float width) {
	float lat = asin(clamp(q.y / GLOBE_R, -1.0, 1.0));
	float lon = atan(q.z, q.x);
	float dl = abs(fract(lat / STEP + 0.5) - 0.5) * STEP * GLOBE_R;
	float dn = abs(fract(lon / STEP + 0.5) - 0.5) * STEP * GLOBE_R * cos(lat);
	float lines = exp(-pow2(dl / width)) + exp(-pow2(dn / width)) * smoothstep(1.45, 1.2, abs(lat));
	// Data points: a few grid crossings light up, each blinking a whole number of times per loop.
	vec2 cell = floor(vec2(lat, lon) / STEP + 0.5);
	float h = hash21(cell + 9.0);
	float blink = step(0.8, h) * spow(0.5 + 0.5 * sin(TAU * (uPhase * (1.0 + floor(h * 4.0)) + h * 3.0)), 6.0);
	vec2 off = vec2(lat, lon) / STEP - cell;
	float dot0 = exp(-dot(off, off) * 60.0) * blink;
	return lines + dot0 * 3.0;
}

vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo) {
	vec3 ro = vec3(0.0, 0.0, 40.0);
	vec3 rd = normalize(vec3(P, 0.0) - ro);
	mat3 R = rotX(0.4) * rotY(STEP * uPhase);
	float flicker = 0.85 + 0.15 * sin(TAU * uPhase * 11.0) * sin(TAU * uPhase * 5.0);
	vec3 tint = mix(GLOW, VIOLET, 0.5 + 0.5 * sin(TAU * uPhase));
	// Ray against the sphere: both crossings.
	vec3 oc = ro - GEM;
	float b = dot(oc, rd);
	float h = b * b - dot(oc, oc) + GLOBE_R * GLOBE_R;
	float scanY = GEM.y + 3.0 - 6.0 * uPhase;
	float scan = exp(-pow2((P.y - scanY) * 7.0));
	if (h > 0.0) {
		float sq = sqrt(h + 1e-6);
		vec3 front = R * (ro + rd * (-b - sq) - GEM);
		vec3 back = R * (ro + rd * (-b + sq) - GEM);
		float rim = 1.0 - sq / GLOBE_R;
		float g = gridLines(front, 0.035) + gridLines(back, 0.03) * 0.3;
		col += g * tint * 0.6 * flicker * (1.0 + 1.5 * scan);
		col += (0.03 + spow(rim, 3.0) * 0.4) * tint * 0.4;
	}
	col += exp(-pow2(max(length(P - GEM.xy) - GLOBE_R, 0.0)) * 3.0) * tint * 0.04;
	// The orbit ring and its satellite.
	mat3 O = rotX(1.25) * rotZ(0.35);
	float ring = 0.0;
	vec3 prev = GEM + O * vec3(GLOBE_R * 1.45, 0.0, 0.0);
	for (int i = 1; i <= 128; i++) {
		float a = TAU * float(i) / 128.0;
		vec3 cur = GEM + O * vec3(cos(a), sin(a), 0.0) * GLOBE_R * 1.45;
		vec2 d = raySeg(ro, rd, prev, cur);
		float behind = d.y > length(GEM - ro) ? 0.35 : 1.0;
		ring += exp(-d.x * d.x / 0.0015) * behind;
		prev = cur;
	}
	vec3 sat = GEM + O * vec3(cos(TAU * uPhase), sin(TAU * uPhase), 0.0) * GLOBE_R * 1.45;
	vec2 sd = raySeg(ro, rd, sat, sat + vec3(0.0, 0.0, 1e-4));
	col += ring * GLOW * 0.5 + (exp(-sd.x * sd.x / 0.01) * 2.0 + exp(-sd.x * sd.x / 0.2) * 0.2) * HOT;
	// The scan band and faint horizontal lines across the hologram.
	col += scan * GLOW * 0.2 * smoothstep(3.2, 1.0, abs(P.x)) * step(abs(P.y - GEM.y), 3.0);
	col += (0.5 + 0.5 * sin(P.y * 40.0)) * GLOW * 0.012 * exp(-length((P - GEM.xy) * vec2(0.4, 0.3)) * 1.2);
	return col;
}
