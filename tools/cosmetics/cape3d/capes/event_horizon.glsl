// Event Horizon - a black hole bending light round itself: its glowing accretion disk turns (faster near the
// middle), the far side of the disk is lensed up over and under the hole, a thin photon ring hugs the shadow, and the
// side of the disk coming towards you burns brighter. Stars behind are bent too.

const vec3 GLOW = vec3(2.4, 1.3, 0.55);
const vec3 HOT = vec3(2.8, 2.5, 2.2);
const vec3 FACE0 = vec3(0.0);
const vec3 FACE1 = vec3(0.0);
const vec3 AMB0 = vec3(0.0);
const vec3 AMB1 = vec3(0.0);
const vec3 FABRIC = vec3(0.01, 0.007, 0.006);
const vec2 LOGO_C = vec2(0.0, 1.0);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define NO_LOGO

#include "kit.glsl"

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

const vec3 BH = vec3(0.0, 1.2, 0.0);
const float RS = 0.75;           // event horizon radius
const float DISK_IN = 1.5, DISK_OUT = 6.0;

// The disk lies in this plane (normal mostly up, tipped a little towards you).
vec3 diskNormal() { return normalize(vec3(0.0, 1.0, 0.18)); }

// Disk texture: rings of hot gas streaming round; inner rings turn faster. Loops by crossfading two passes.
float diskNoise(float r, float a) {
	float speed = 2.2 / sqrt(r);
	float fa = fract(uPhase), fb = fract(uPhase + 0.5);
	float ra = a - TAU * speed * fa * 0.25;
	float rb = a - TAU * speed * fb * 0.25 + 1.3;
	float na = fbm3(vec3(cos(ra) * r * 1.5, sin(ra) * r * 1.5, r * 3.0), 5);
	float nb = fbm3(vec3(cos(rb) * r * 1.5, sin(rb) * r * 1.5, r * 3.0 + 7.0), 5);
	return mix(na, nb, abs(1.0 - 2.0 * fa));
}

vec3 diskColor(vec3 p, vec3 rd) {
	vec3 n = diskNormal();
	vec3 u = normalize(cross(n, vec3(0.0, 0.0, 1.0)));
	vec3 w = cross(n, u);
	vec3 q = p - BH;
	float r = length(q - n * dot(q, n));
	float a = atan(dot(q, w), dot(q, u));
	float t = clamp((r - DISK_IN) / (DISK_OUT - DISK_IN), 0.0, 1.0);
	float n1 = diskNoise(r, a);
	float rings = 0.75 + 0.25 * sin(r * 7.0 + n1 * 9.0);
	vec3 temp = mix(vec3(2.6, 2.2, 1.7), vec3(2.2, 0.8, 0.25), smoothstep(0.0, 0.45, t));
	temp = mix(temp, vec3(0.6, 0.12, 0.04), smoothstep(0.45, 1.0, t));
	float dens = smoothstep(0.0, 0.06, t) * (1.0 - smoothstep(0.75, 1.0, t)) * (0.35 + 0.9 * n1) * rings;
	// Doppler: gas coming towards you is brighter.
	vec3 vel = normalize(cross(n, q));
	float doppler = 1.0 + 0.75 * dot(vel, -rd);
	return temp * dens * doppler * 0.65;
}

vec3 capeBackground(vec2 P, float halo) {
	vec3 ro = vec3(0.0, 0.0, 40.0);
	vec3 rd = normalize(vec3(P, 0.0) - ro);
	// Start the bent march near the hole.
	float t0 = (ro.z - 9.0) / -rd.z;
	vec3 p = ro + rd * t0;
	vec3 dir = rd;
	vec3 col = vec3(0.0);
	float alpha = 1.0;
	float closest = 1e9;
	bool captured = false;
	vec3 n = diskNormal();
	for (int i = 0; i < 260; i++) {
		vec3 q = p - BH;
		float r = length(q);
		closest = min(closest, r);
		if (r < RS) { captured = true; break; }
		float dt = clamp(r * 0.06, 0.02, 0.25);
		// Bend towards the hole (a 1/r^2 pull, strong enough to wrap light round it).
		dir = normalize(dir - q / (r * r * r) * 1.35 * RS * dt);
		vec3 np = p + dir * dt;
		// Crossing the disk plane: add its light.
		float s0 = dot(p - BH, n), s1 = dot(np - BH, n);
		if (s0 * s1 < 0.0) {
			vec3 hit = mix(p, np, s0 / (s0 - s1));
			float hr = length(hit - BH);
			if (hr > DISK_IN && hr < DISK_OUT) {
				vec3 dc = diskColor(hit, dir);
				float a = clamp(length(dc) * 0.6, 0.0, 0.9);
				col += dc * alpha;
				alpha *= 1.0 - a;
			}
		}
		p = np;
		if (r > 14.0 && dot(q, dir) > 0.0) break;
	}
	if (!captured) {
		// Stars in the bent direction.
		vec2 sd = dir.xy / max(-dir.z, 0.05) * 9.0;
		col += sparkles(sd, 1.4, 0.0, 0.08) * vec3(0.85, 0.9, 1.0) * 0.35 * alpha;
		col += vec3(0.006, 0.005, 0.008) * alpha;
	}
	// The photon ring: light that almost orbited.
	col += exp(-pow2((closest - RS * 1.55) * 9.0)) * HOT * 0.5 * alpha * (captured ? 0.0 : 1.0);
	if (!captured) col += exp(-max(closest - RS, 0.0) * 1.2) * GLOW * 0.03;
	return col;
}
