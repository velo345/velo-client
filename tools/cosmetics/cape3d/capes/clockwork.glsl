// Clockwork - the inside of a watch: brass gears (capes/clockwork.py) meshing tooth for tooth as they turn, ruby
// jewels at their arbours, a balance wheel swinging back and forth on its hairspring, a glint sliding over the brass,
// all on a dark plate finished with perlage, engraved and screwed down.
// @text "VELO CLIENT"

const vec3 GLOW = vec3(2.2, 1.4, 0.5);
const vec3 HOT = vec3(2.6, 2.2, 1.5);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.01, 0.007, 0.004);
const vec3 AMB1 = vec3(0.05, 0.04, 0.03);
const vec3 FABRIC = vec3(0.014, 0.009, 0.006);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

uniform vec4 uGears[6];       // centre.xy, pitch radius, teeth
uniform vec4 uGearMotion[6];  // rotation offset, direction
uniform int uGearCount;
uniform float uTeethPerLoop;

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const vec2 BALANCE = vec2(3.3, 5.7);

// A gear's signed distance (teeth, rim, spokes, hub) in its own turned frame.
float gearD(vec2 q, float rp, float teeth) {
	float r = length(q);
	float a = atan(q.y, q.x);
	float u = a * teeth / TAU;
	float w = abs(u - floor(u + 0.5));           // 0 at a tooth's centre .. 0.5 between teeth
	float h = 0.44;
	float outer = rp - h * 0.5 + h * smoothstep(0.3, 0.16, w);
	float d = r - outer;
	// Lighten it: an open ring between rim and hub, crossed by spokes.
	float spokes = teeth > 16.0 ? 6.0 : 4.0;
	float rimIn = rp * 0.74;
	float hub = max(0.32, rp * 0.24);
	float sa = abs(fract(a * spokes / TAU) - 0.5) * TAU / spokes * r;
	float hole = max(max(r - rimIn, hub - r), 0.13 + rp * 0.03 - sa);
	d = max(d, -hole);
	// The arbour hole.
	return max(d, 0.13 - r);
}

float gearAngle(int i) {
	return uGearMotion[i].x + uGearMotion[i].y * TAU * uTeethPerLoop / uGears[i].w * uPhase;
}

// Polished brass: the faces have a sunburst brush (fine radial lines that catch the light by their angle), the edges
// a sharp polished chamfer, and the whole gear a soft reflection of the room.
vec3 brass(float d, vec2 grad, vec2 local, float ang) {
	vec3 L = normalize(vec3(-0.6, 0.7, 0.9));
	float bevel = 1.0 - smoothstep(0.0, 0.07, -d);
	vec3 n = normalize(vec3(-grad * bevel * 2.2, 1.0));
	float diff = max(dot(n, L), 0.0);
	float worldA = atan(local.y, local.x) + ang;
	float brush = spow(abs(cos(worldA - 2.3)), 6.0) * (0.6 + 0.4 * hash11(floor(atan(local.y, local.x) * 180.0)));
	vec3 base = mix(vec3(0.3, 0.17, 0.05), vec3(0.72, 0.47, 0.16), diff);
	base += brush * vec3(0.35, 0.25, 0.1) * (1.0 - bevel);
	// Engraved rings on the face.
	float rr = length(local);
	base *= 1.0 - 0.25 * (1.0 - smoothstep(0.0, 0.015, abs(fract(rr * 2.2) - 0.5) - 0.47));
	// The chamfer: bright where it faces the light, dark where it turns away.
	base += bevel * spow(max(dot(n, normalize(L + vec3(0.0, 0.0, 1.0))), 0.0), 20.0) * vec3(1.6, 1.25, 0.7);
	base *= 1.0 - bevel * (1.0 - diff) * 0.6;
	return base;
}

float screwD(vec2 q, float r) { return length(q) - r; }

vec3 screw(vec2 q, float r, float slotA) {
	float d = length(q);
	vec3 L = normalize(vec3(-0.6, 0.7, 0.9));
	vec3 n = normalize(vec3(q / r * 0.8, 1.0));
	vec3 c = vec3(0.5, 0.5, 0.55) * (0.3 + 0.8 * max(dot(n, L), 0.0)) + spow(max(dot(n, normalize(L + vec3(0, 0, 1))), 0.0), 40.0) * 0.8;
	vec2 sq = vec2(q.x * cos(slotA) - q.y * sin(slotA), q.x * sin(slotA) + q.y * cos(slotA));
	c *= 0.3 + 0.7 * smoothstep(0.02, 0.035, abs(sq.y));
	return c;
}

vec3 capeBackground(vec2 P, float halo) {
	// The plate: dark rhodium-grey with perlage (overlapping circular grain) and a soft vignette.
	vec2 g = P * 2.2;
	vec2 id = floor(g);
	vec2 f = fract(g) - 0.5;
	float perl = 0.5 + 0.5 * sin(length(f) * 40.0 + hash21(id) * 6.0);
	vec3 c = vec3(0.025, 0.024, 0.026) * (0.8 + 0.3 * perl) * (0.6 + 0.4 * smoothstep(10.0, 0.0, length(P)));
	// Shadows of the gears on the plate, then the gears.
	for (int i = 0; i < 6; i++) {
		if (i >= uGearCount) break;
		vec2 q = P - uGears[i].xy - vec2(0.12, -0.16);
		float ang = gearAngle(i);
		q = vec2(q.x * cos(-ang) - q.y * sin(-ang), q.x * sin(-ang) + q.y * cos(-ang));
		float d = gearD(q, uGears[i].z, uGears[i].w);
		c *= 1.0 - 0.55 * (1.0 - smoothstep(-0.05, 0.25, d));
	}
	float sweep = P.x * 0.5 + P.y * 0.5 - (-10.0 + 20.0 * uPhase);
	for (int i = 0; i < 6; i++) {
		if (i >= uGearCount) break;
		vec2 q = P - uGears[i].xy;
		float ang = gearAngle(i);
		q = vec2(q.x * cos(-ang) - q.y * sin(-ang), q.x * sin(-ang) + q.y * cos(-ang));
		float d = gearD(q, uGears[i].z, uGears[i].w);
		// The gear's side wall: the same shape a little down-right, dark brass, so it reads thick.
		vec2 qs = P - uGears[i].xy + vec2(-0.06, 0.08);
		qs = vec2(qs.x * cos(-ang) - qs.y * sin(-ang), qs.x * sin(-ang) + qs.y * cos(-ang));
		float ds = gearD(qs, uGears[i].z, uGears[i].w);
		c = mix(c, vec3(0.16, 0.09, 0.025), (1.0 - smoothstep(-0.01, 0.01, ds)) * smoothstep(-0.02, 0.01, d));
		if (d < 0.02) {
			const float e = 0.01;
			vec2 grad = vec2(gearD(q + vec2(e, 0.0), uGears[i].z, uGears[i].w) - d, gearD(q + vec2(0.0, e), uGears[i].z, uGears[i].w) - d) / e;
			// Turn the gradient back into screen space so the light stays put as the gear turns.
			grad = vec2(grad.x * cos(ang) - grad.y * sin(ang), grad.x * sin(ang) + grad.y * cos(ang));
			vec3 b = brass(d, grad, q, ang);
			b += exp(-sweep * sweep * 0.3) * vec3(0.5, 0.38, 0.2);
			c = mix(c, b, 1.0 - smoothstep(-0.01, 0.01, d));
		}
		// The ruby jewel at the arbour.
		float jr = length(P - uGears[i].xy);
		float jewel = 1.0 - smoothstep(0.17, 0.19, jr);
		vec3 ruby = vec3(0.5, 0.02, 0.05) + spow(1.0 - jr / 0.18, 3.0) * vec3(1.2, 0.2, 0.25) + exp(-length(P - uGears[i].xy - vec2(-0.05, 0.05)) * 40.0) * vec3(1.5);
		c = mix(c, ruby, jewel);
		c = mix(c, vec3(0.45, 0.42, 0.4), (1.0 - smoothstep(0.0, 0.025, abs(jr - 0.21))) * 0.8);
	}
	// The balance wheel: a rim with two crossing arms and timing screws, swinging back and forth twice a loop; under
	// it the hairspring, an Archimedean spiral that winds and unwinds with the swing.
	float swing = 1.6 * sin(TAU * uPhase * 2.0);
	vec2 bq = P - BALANCE;
	vec2 bw = vec2(bq.x * cos(-swing) - bq.y * sin(-swing), bq.x * sin(-swing) + bq.y * cos(-swing));
	float br = length(bw);
	float ba = atan(bw.y, bw.x);
	float pitch = 0.07 + 0.006 * sin(TAU * uPhase * 2.0);
	float turn = (br - 0.15) / pitch - ba / TAU;
	float spring = abs(fract(turn) - 0.5) * pitch - 0.008;
	spring = max(spring, max(br - 0.75, 0.15 - br));
	c = mix(c, vec3(0.55, 0.55, 0.62), (1.0 - smoothstep(-0.006, 0.006, spring)) * 0.9);
	float rim = abs(br - 1.15) - 0.09;
	float arms = max(min(abs(bw.y), abs(bw.x)) - 0.05, br - 1.15);
	float wheel = min(rim, arms);
	vec2 wg = normalize(bw + 1e-4) * sign(br - 1.15);
	c = mix(c, vec3(0.1, 0.06, 0.02), (1.0 - smoothstep(-0.01, 0.01, min(rim, arms) - 0.03)) * 0.6);
	c = mix(c, brass(wheel, wg, bw, swing), 1.0 - smoothstep(-0.01, 0.01, wheel));
	for (int k = 0; k < 8; k++) {
		float a = TAU * float(k) / 8.0 + 0.2;
		vec2 sp = vec2(cos(a), sin(a)) * 1.27;
		float sd = length(bw - sp) - 0.07;
		c = mix(c, vec3(0.75, 0.6, 0.3) * (0.6 + 0.6 * (0.5 + 0.5 * dot(normalize(bw - sp + 1e-4), vec2(-0.6, 0.7)))), 1.0 - smoothstep(-0.01, 0.01, sd));
	}
	c = mix(c, vec3(0.5, 0.03, 0.06), 1.0 - smoothstep(0.1, 0.12, length(bq)));
	// Plate screws with polished heads, and the engraving low on the plate.
	vec2 screws[4] = vec2[4](vec2(-4.2, -6.9), vec2(4.3, 7.2), vec2(-4.3, 7.3), vec2(0.6, -7.2));
	for (int k = 0; k < 4; k++) {
		vec2 sq = P - screws[k];
		c *= 1.0 - 0.5 * (1.0 - smoothstep(0.2, 0.32, length(sq - vec2(-0.05, 0.05))));
		c = mix(c, screw(sq, 0.22, float(k) * 0.9), 1.0 - smoothstep(0.21, 0.23, length(sq)));
	}
	float tw = 5.0;
	float td = text2((P - vec2(0.0, -6.0)) / tw) * tw;
	float eng = 1.0 - smoothstep(-0.004, 0.004, td);
	c = mix(c, c * 0.35 + vec3(0.06, 0.05, 0.04), eng * 0.9);
	c += (1.0 - smoothstep(0.0, 0.01, abs(td + 0.006))) * vec3(0.05, 0.045, 0.04);
	return c;
}
