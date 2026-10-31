// Velo Staff - worn only by the team: the sharp Velo logo in matte black with white-lit edges, STAFF in chrome
// below, all on matte black. A red scan line sweeps down once a loop; as it crosses the logo its edges flash red
// ("verified") and STAFF's letters light up one by one.
// @text "STAFF"

const vec3 GLOW = vec3(1.5, 1.5, 1.6);
const vec3 HOT = vec3(2.4, 2.4, 2.5);
const vec3 RED = vec3(2.3, 0.08, 0.1);
const vec3 FACE0 = vec3(0.004, 0.004, 0.005);
const vec3 FACE1 = vec3(0.025, 0.025, 0.03);
const vec3 AMB0 = vec3(0.004, 0.004, 0.005);
const vec3 AMB1 = vec3(0.04, 0.04, 0.045);
const vec3 FABRIC = vec3(0.008, 0.008, 0.009);
const vec2 LOGO_C = vec2(0.0, 1.6);
const float LOGO_S = 8.2;
const float LOGO_TH = 0.34;
const float LOGO_CH = 0.12;
#define LOGO_EDGE
#define OVERLAY

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

float scanY() { return 9.0 - 18.0 * uPhase; }

vec3 logoEdge(vec3 lp, float t, float pl) {
	vec3 col = mix(HOT * 0.85, GLOW * 0.5, smoothstep(0.0, 0.7, t)) * (0.55 + 1.0 * pl);
	// Where the scan has just passed, the edges glow red and cool back to white.
	float since = lp.y - scanY();
	float verify = since > 0.0 ? exp(-since * 0.55) : 0.0;
	return mix(col, RED * (0.7 + 0.6 * (1.0 - t)), verify);
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 c = mix(vec3(0.01, 0.01, 0.011), vec3(0.002, 0.002, 0.0025), smoothstep(0.0, 9.0, length((P - LOGO_C) * vec2(1.0, 0.8))));
	c *= 0.85 + 0.3 * fbm3(vec3(P * 30.0, 0.0), 2);
	c += halo * GLOW * 0.05;
	// A thin red rule under the logo, breathing.
	float rule = (1.0 - smoothstep(0.0, 0.025, abs(P.y + 3.3))) * (1.0 - smoothstep(2.6, 3.0, abs(P.x)));
	c += rule * RED * (0.35 + 0.25 * sin(TAU * uPhase * 2.0));
	// STAFF in chrome; each letter lights as the scan reaches its row, left to right.
	float tw = 12.0;
	vec2 tq = (P - vec2(0.0, -4.7)) / tw;
	float td = text2(tq) * tw;
	float letters = 1.0 - smoothstep(-0.006, 0.006, td);
	float lit = smoothstep(-4.4, -5.2 - (tq.x + 0.5) * 1.5, scanY());
	vec3 chrome = mix(vec3(0.2, 0.2, 0.22), vec3(0.75, 0.75, 0.8), smoothstep(-4.9, -4.3, P.y));
	c = mix(c, chrome + RED * 0.25 * lit + vec3(0.4) * lit, letters);
	return c;
}

// The scan line itself, over everything, with a faint red wash just behind it.
vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo) {
	float d = P.y - scanY();
	col += exp(-pow2(d * 18.0)) * RED * 0.9 + (d > 0.0 ? exp(-d * 2.5) * RED * 0.04 : vec3(0.0));
	return col;
}
