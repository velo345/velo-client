// Shared code for the 3D cape scenes (see render_cape3d.py). Everything is in cape units: the outside face is
// x -5..5, y -8..8 (y up, top = shoulders), z towards the viewer. Every animation is a function of uPhase (0..1)
// built from whole-number periods, so the last frame runs straight into the first.
#version 330

uniform vec2 uRes;        // size of the face being rendered, in pixels
uniform vec2 uFaceSize;   // its size in cape units (10x16, 1x16, 10x1)
uniform int uFace;        // 0 outside, 1 inside, 2 left edge, 3 right edge, 4 top edge, 5 bottom edge
uniform float uPhase;
uniform sampler2D uLogo;  // r: signed distance to the Velo logo, in logo widths (negative inside)
uniform float uLogoMargin;
uniform sampler2D uLogoArt; // the logo's own colours, same mapping as uLogo (rgba, row 0 = top)
out vec4 fragColor;

#define TAU 6.28318530718
#define PI 3.14159265359

// ---- hashes & noise ----

float hash11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2 hash22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }
vec3 hash33(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.xxy + p.yxx) * p.zyx); }

float noise3(vec3 p) {
	vec3 i = floor(p), f = fract(p);
	f = f * f * (3.0 - 2.0 * f);
	float n000 = hash21(i.xy + i.z * 57.0), n100 = hash21(i.xy + vec2(1, 0) + i.z * 57.0);
	float n010 = hash21(i.xy + vec2(0, 1) + i.z * 57.0), n110 = hash21(i.xy + vec2(1, 1) + i.z * 57.0);
	float n001 = hash21(i.xy + (i.z + 1.0) * 57.0), n101 = hash21(i.xy + vec2(1, 0) + (i.z + 1.0) * 57.0);
	float n011 = hash21(i.xy + vec2(0, 1) + (i.z + 1.0) * 57.0), n111 = hash21(i.xy + vec2(1, 1) + (i.z + 1.0) * 57.0);
	return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y), mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
}

float fbm3(vec3 p, int oct) {
	float a = 0.5, s = 0.0, t = 0.0;
	for (int i = 0; i < 8; i++) {
		if (i >= oct) break;
		s += a * noise3(p); t += a;
		p = p * 2.03 + vec3(1.7, 9.2, 4.1);
		a *= 0.5;
	}
	return s / t;
}

// A point that circles once per loop: feeding it into noise gives motion that loops seamlessly.
vec3 loopOffset(float radius, int turns, float seed) {
	float a = TAU * (uPhase * float(turns) + seed);
	return vec3(cos(a), sin(a), 0.0) * radius;
}

// F1, F2 of 3D Voronoi - (F2 - F1) small = on a crack.
vec2 voronoi3(vec3 p) {
	vec3 i = floor(p), f = fract(p);
	float d1 = 8.0, d2 = 8.0;
	for (int z = -1; z <= 1; z++) for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
		vec3 g = vec3(x, y, z);
		vec3 o = hash33(i + g);
		float d = length(g + o - f);
		if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
	}
	return vec2(d1, d2);
}

// ---- SDF helpers ----

mat3 rotX(float a) { float c = cos(a), s = sin(a); return mat3(1, 0, 0, 0, c, s, 0, -s, c); }
mat3 rotY(float a) { float c = cos(a), s = sin(a); return mat3(c, 0, -s, 0, 1, 0, s, 0, c); }
mat3 rotZ(float a) { float c = cos(a), s = sin(a); return mat3(c, s, 0, -s, c, 0, 0, 0, 1); }

float smin(float a, float b, float k) { float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0); return mix(b, a, h) - k * h * (1.0 - h); }

// A chain link: long axis y, ring in the xy plane. le = half straight length, r1 = ring radius, r2 = wire radius.
float sdLink(vec3 p, float le, float r1, float r2) {
	vec3 q = vec3(p.x, max(abs(p.y) - le, 0.0), p.z);
	return length(vec2(length(q.xy) - r1, q.z)) - r2;
}

float sdOcta(vec3 p, float s) { p = abs(p); return (p.x + p.y + p.z - s) * 0.57735027; }

// The Velo logo's 2D signed distance, q in logo space (-0.5..0.5, y up), result in logo widths.
float logo2(vec2 q) {
	vec2 uv = (vec2(q.x, -q.y) + 0.5 + uLogoMargin) / (1.0 + 2.0 * uLogoMargin);
	vec2 c = clamp(uv, 0.0, 1.0);
	float d = texture(uLogo, c).r;
	// Outside the texture: the edge value plus the distance to it (a safe bound).
	return d + length((uv - c) * (1.0 + 2.0 * uLogoMargin));
}

// The "VELO CLIENT" wordmark (2048x256 SDF): q in units of the wordmark's box width, x -0.5..0.5, y up.
uniform sampler2D uText;
float text2(vec2 q) {
	vec2 uv = vec2(q.x + 0.5, 0.5 - q.y * 8.0);
	vec2 c = clamp(uv, 0.0, 1.0);
	return texture(uText, c).r + length((uv - c) * vec2(1.0, 0.125));
}

// The logo's own colour at logo-space q (linear), alpha in w.
vec4 logoArt(vec2 q) {
	vec2 uv = (vec2(q.x, -q.y) + 0.5 + uLogoMargin) / (1.0 + 2.0 * uLogoMargin);
	vec4 c = texture(uLogoArt, clamp(uv, 0.0, 1.0));
	return vec4(pow(c.rgb, vec3(2.2)), c.a);
}

// The logo extruded with rounded bevels: centre c, width s, half depth th, bevel radius bev.
float sdLogo(vec3 p, vec2 c, float s, float th, float bev) {
	float d2 = logo2((p.xy - c) / s) * s;
	vec2 w = vec2(d2 + bev, abs(p.z) - th + bev);
	return min(max(w.x, w.y), 0.0) + length(max(w, 0.0)) - bev;
}

// The logo extruded with hard 45-degree chamfers instead of round bevels: crisp, faceted edges.
float sdLogoSharp(vec3 p, vec2 c, float s, float th, float ch) {
	float d2 = logo2((p.xy - c) / s) * s;
	float az = abs(p.z);
	return max(max(d2, az - th), (d2 + az - th + ch) * 0.70710678);
}

// ---- shading helpers ----

// NB: pow(0.0, y) and sqrt(0.0) can come out NaN on the NVIDIA driver here - keep their arguments above zero.
vec3 aces(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }

float fresnel(vec3 n, vec3 v, float f0) { return f0 + (1.0 - f0) * pow(1.0 - max(dot(n, v), 0.0), 5.0); }

// Face-local uv (0..1, y up) of this fragment.
vec2 faceUv() { return gl_FragCoord.xy / uRes; }

// The scene implements this: linear HDR colour for this face position (cape units on the outside face).
vec3 capeColor(vec2 uv, int face);

void main() {
	fragColor = vec4(capeColor(faceUv(), uFace), 1.0);
}
