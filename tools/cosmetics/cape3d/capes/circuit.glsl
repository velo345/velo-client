// Circuit - the original Circuit layout (traces, pads, chips; capes/circuit.py), redrawn sharp: data pulses race along
// the traces like comets and pads flash where they land; once a loop a power surge floods out through every trace at
// once, the chips light up and an arc jumps between two of them.

const vec3 GLOW = vec3(0.2, 1.4, 2.2);
const vec3 HOT = vec3(1.5, 2.4, 2.7);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.003, 0.008, 0.01);
const vec3 AMB1 = vec3(0.03, 0.06, 0.07);
const vec3 FABRIC = vec3(0.006, 0.012, 0.016);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

uniform vec4 uSegs[400];     // a.xy, b.xy
uniform vec4 uSegInfo[400];  // trace, distance along the trace at a, length
uniform int uSegCount;
uniform float uTraceLen[32];
uniform vec4 uTraceEnds[32]; // start.xy, end.xy
uniform int uTraceCount;
uniform vec4 uChips[4];      // centre.xy, half size.xy

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const float TAIL = 2.4;
const float SURGE_AT = 0.58;

// Where trace k's pulse head is (distance along it); it runs past the end by the tail length, then starts over.
float pulseHead(int k) {
	float speed = 1.0 + float(k % 2);
	return fract(uPhase * speed + float(k) * 0.37) * (uTraceLen[k] + TAIL + 1.0);
}

// The surge front: 0 before it, racing out along every trace, the afterglow gone by the end of the loop.
float surgeT() { return clamp((uPhase - SURGE_AT) / (1.0 - SURGE_AT), 0.0, 1.0); }
float surgeFront() { return surgeT() * 16.0; }
float surgeFade() { return (1.0 - smoothstep(0.7, 1.0, surgeT())) * step(SURGE_AT, uPhase); }

float sdBox2(vec2 p, vec2 b) { vec2 d = abs(p) - b; return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0); }

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = vec3(0.006, 0.011, 0.018) + vec3(0.0, 0.003, 0.006) * smoothstep(8.0, -8.0, P.y);
	c *= 0.9 + 0.2 * fbm3(vec3(P * 2.0, 0.0), 3);
	float surge = surgeFade();
	float front = surgeFront();

	// Nearest trace segment: distance, which trace, how far along it.
	float d = 1e9, along = 0.0;
	int trace = -1;
	for (int i = 0; i < 400; i++) {
		if (i >= uSegCount) break;
		vec2 a = uSegs[i].xy, b = uSegs[i].zw;
		vec2 ba = b - a;
		float t = clamp(dot(P - a, ba) / dot(ba, ba), 0.0, 1.0);
		float di = length(P - a - ba * t);
		if (di < d) { d = di; trace = int(uSegInfo[i].x); along = uSegInfo[i].y + t * uSegInfo[i].z; }
	}
	float line = 1.0 - smoothstep(0.05, 0.07, d);
	vec3 base = vec3(0.16, 0.59, 0.75) * 0.55;
	c = mix(c, base, line);
	c += exp(-d * 9.0) * GLOW * 0.025;
	if (trace >= 0) {
		// The comet: brightest at its head, fading along its tail.
		float x = pulseHead(trace) - along;
		float comet = x > 0.0 && x < TAIL && along <= uTraceLen[trace] ? spow(1.0 - x / TAIL, 2.2) : 0.0;
		vec3 cometCol = mix(GLOW, HOT, spow(1.0 - x / TAIL, 4.0));
		c += comet * (line * cometCol * 1.6 + exp(-d * 6.0) * GLOW * 0.35);
		// The surge: a bright front racing out along every trace, a glow left behind it.
		float sf = exp(-pow2((along - front) * 2.2)) + (along < front ? exp(-(front - along) * 0.6) * 0.45 : 0.0);
		c += sf * surge * (line * HOT * 1.3 + exp(-d * 5.0) * GLOW * 0.3);
	}

	// Pads: rings at both ends of every trace; the far one flashes when its pulse lands.
	for (int k = 0; k < 32; k++) {
		if (k >= uTraceCount) break;
		for (int e = 0; e < 2; e++) {
			vec2 pc = e == 0 ? uTraceEnds[k].xy : uTraceEnds[k].zw;
			float r = length(P - pc);
			float ring = 1.0 - smoothstep(0.03, 0.05, abs(r - 0.15));
			float hole = 1.0 - smoothstep(0.05, 0.07, r);
			float land = e == 1 ? exp(-max(pulseHead(k) - uTraceLen[k], 0.0) * 2.5) * step(uTraceLen[k], pulseHead(k)) : 0.0;
			float hit = e == 0 ? surge * exp(-front * 0.8) : land;
			c = mix(c, vec3(0.27, 0.82, 0.94) * 0.7, ring);
			c = mix(c, vec3(0.01, 0.02, 0.03), hole);
			c += (ring * HOT * 1.4 + exp(-r * 4.0) * GLOW * 0.5) * hit;
		}
	}

	// Chips: raised dark blocks with a lit outline and pin ticks; they power up with the surge.
	for (int k = 0; k < 4; k++) {
		vec2 q = P - uChips[k].xy;
		vec2 hs = uChips[k].zw;
		float cd = sdBox2(q, hs);
		// Pins along the top and bottom edges.
		float pin = (1.0 - smoothstep(0.02, 0.035, abs(fract((q.x + hs.x) / 0.25) - 0.5) * 0.25)) * step(abs(q.x), hs.x - 0.05)
			* step(abs(abs(q.y) - hs.y - 0.1), 0.1);
		c = mix(c, vec3(0.24, 0.74, 0.9) * 0.6, pin);
		c *= 1.0 - 0.5 * exp(-max(cd, 0.0) * 6.0) * step(0.0, cd);
		if (cd < 0.0) {
			// Body with a bevel lit from the top left, the die grid inside glowing as the surge reaches it.
			float bevel = clamp(-cd / 0.12, 0.0, 1.0);
			vec3 body = vec3(0.055, 0.086, 0.133) * (0.75 + 0.25 * bevel);
			body += (1.0 - bevel) * (q.x < 0.0 || q.y > 0.0 ? 0.04 : -0.02);
			vec2 g = abs(fract(q * 4.0) - 0.5);
			float grid = (1.0 - smoothstep(0.0, 0.04, min(g.x, g.y))) * smoothstep(0.25, 0.35, -cd);
			float power = surge * smoothstep(-0.5, 0.5, front - length(uChips[k].xy) * 0.9 - 2.0);
			body += grid * GLOW * (0.03 + 0.5 * power) + GLOW * power * 0.12 * smoothstep(0.0, 0.6, -cd);
			c = body;
		}
		float outline = 1.0 - smoothstep(0.02, 0.045, abs(cd));
		c += outline * mix(vec3(0.24, 0.74, 0.9) * 0.6, HOT, surge * 0.7);
		c += exp(-abs(cd) * 5.0) * GLOW * 0.04 * (1.0 + 4.0 * surge);
	}

	// The arc between the first two chips at the surge's peak.
	float peak = surge * exp(-pow2((surgeT() - 0.25) * 5.0));
	c += bolt(P, uChips[0].xy, uChips[1].xy, 4.0 + floor(uPhase * 8.0), 0.04) * peak * HOT * 1.2;

	// A slow scan line, and the whole board brightening a touch with the surge.
	c += exp(-pow2((P.y - (8.0 - 16.0 * uPhase)) * 6.0)) * vec3(0.0, 0.12, 0.15);
	c *= 1.0 + surge * exp(-surgeT() * 6.0) * 0.6;
	return c;
}
