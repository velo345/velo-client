// The cape kit: everything the 0.9.97 HD capes share - the sharp chamfered 3D Velo logo, its glow and pulse, the ray
// march, the frame, the inside and the edges. A cape file sets its palette and hooks, then includes this file (the include line, see render_cape3d.py).
//
// The cape defines its constants and #defines before the include, its functions before or after it:
//   const vec3 GLOW, HOT          edge glow colour, white-hot edge colour
//   const vec3 FACE0, FACE1       logo face gradient (bottom, top)
//   const vec3 AMB0, AMB1         reflection environment (below, above)
//   const vec3 FABRIC             inside base colour
//   const vec2 LOGO_C; const float LOGO_S, LOGO_TH, LOGO_CH
//   float extraD(vec3 p)                                 extra 3D geometry, 1e9 for none
//   vec3 shadeExtra(vec3 p, vec3 n, vec3 v)              its shading
//   vec3 capeBackground(vec2 P, float halo)              what's behind (halo: 0..1 near the logo)
// Optional, enabled with a #define before the include:
//   LOGO_FACE:  vec3 logoFace(vec3 lp, vec3 n, vec3 v, float din, float pl)   the logo's front face
//   LOGO_EDGE:  vec3 logoEdge(vec3 lp, float t, float pl)                       the chamfers (t 0 = outer edge)
//   OVERLAY:    vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo)                drawn over everything (rain, flashes)
//   INSIDE:     vec3 capeInside(vec2 P, vec2 uv)                                the inside face
//   NO_LOGO:    no logo at all
//   CUSTOM_COLOR: the cape defines capeColor() itself (e.g. to render outside() several times for a glitch)

// The cape's hooks may come after the include (so they can use the helpers below): declared here.
float extraD(vec3 p);
vec3 shadeExtra(vec3 p, vec3 n, vec3 v);
vec3 capeBackground(vec2 P, float halo);
#ifdef LOGO_FACE
vec3 logoFace(vec3 lp, vec3 n, vec3 v, float din, float pl);
#endif
#ifdef LOGO_EDGE
vec3 logoEdge(vec3 lp, float t, float pl);
#endif
#ifdef OVERLAY
vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo);
#endif
#ifdef INSIDE
vec3 capeInside(vec2 P, vec2 uv);
#endif

// ---- loop-safe helpers ----

float pow2(float x) { return x * x; }
float spow(float x, float e) { return pow(1e-6 + max(x, 0.0), e); }

// Noise that scrolls by `travel` per loop and still loops: two layers half a period apart, crossfaded.
float flowNoise(vec3 p, vec3 travel, int oct) {
	float a = fract(uPhase), b = fract(uPhase + 0.5);
	float na = fbm3(p - travel * a, oct);
	float nb = fbm3(p - travel * b + vec3(17.3, 5.1, 9.7), oct);
	float w = abs(1.0 - 2.0 * a);
	return mix(na, nb, w);
}

// Rising motes: `rise` whole cells per loop, `density` 0..1, each twinkles a whole number of times per loop.
float motes(vec2 P, float scale, float rise, float density, float size) {
	vec2 g = vec2(P.x * scale, P.y * scale - uPhase * rise);
	vec2 cell = floor(g);
	vec2 h = hash22(cell);
	vec2 f = fract(g) - (0.2 + 0.6 * h);
	float tw = 0.5 + 0.5 * sin(TAU * (uPhase * (1.0 + floor(h.y * 3.0)) + h.x));
	return (1.0 - smoothstep(0.0, size, length(f))) * step(1.0 - density, hash21(cell + 3.1)) * (0.2 + 0.8 * tw);
}

// Four-point sparkle stars on the same grid.
float sparkles(vec2 P, float scale, float rise, float density) {
	vec2 g = vec2(P.x * scale, P.y * scale - uPhase * rise);
	vec2 cell = floor(g);
	vec2 h = hash22(cell);
	vec2 f = fract(g) - (0.2 + 0.6 * h);
	float tw = 0.5 + 0.5 * sin(TAU * (uPhase * (1.0 + floor(h.y * 3.0)) + h.x));
	float star = exp(-abs(f.x) * 60.0) * exp(-abs(f.y) * 9.0) + exp(-abs(f.y) * 60.0) * exp(-abs(f.x) * 9.0);
	star += 1.0 - smoothstep(0.0, 0.035, length(f));
	return star * step(1.0 - density, hash21(cell + 3.1)) * tw * tw;
}

// A forked lightning bolt from `a` to `b` in 2D: distance-based glow (returns brightness).
float bolt(vec2 P, vec2 a, vec2 b, float seed, float width) {
	vec2 ab = b - a;
	float len = length(ab);
	vec2 dir = ab / len;
	vec2 nrm = vec2(-dir.y, dir.x);
	float t = clamp(dot(P - a, dir) / len, 0.0, 1.0);
	// Jagged offset: a few octaves of noise along the bolt, pinned at both ends.
	float off = (fbm3(vec3(t * 7.0, seed, 0.0), 5) - 0.5) * len * 0.35 * sin(t * PI);
	float d = abs(dot(P - a, nrm) - off);
	float along = dot(P - a, dir);
	d = along < 0.0 || along > len ? 1e3 : d;
	return exp(-d / width) * 0.6 + exp(-d * d / (width * width * 0.08));
}

// ---- the logo ----

mat3 logoRot() { return rotY(0.16 * sin(TAU * uPhase)) * rotX(0.06 * cos(TAU * uPhase)); }
vec3 toLogo(vec3 p) { vec3 c = vec3(LOGO_C, 0.0); return logoRot() * (p - c) + c; }
float logoD(vec3 p) {
#ifdef NO_LOGO
	return 1e9;
#else
	return sdLogoSharp(toLogo(p), LOGO_C, LOGO_S, LOGO_TH, LOGO_CH);
#endif
}

vec2 kitMap(vec3 p) {
	float l = logoD(p);
	float e = extraD(p);
	return l < e ? vec2(l, 1.0) : vec2(e, 2.0);
}

vec3 kitNormal(vec3 p) {
	const vec2 e = vec2(0.0008, -0.0008);
	return normalize(e.xyy * kitMap(p + e.xyy).x + e.yyx * kitMap(p + e.yyx).x + e.yxy * kitMap(p + e.yxy).x + e.xxx * kitMap(p + e.xxx).x);
}

vec3 keyDir() {
	float a = TAU * uPhase;
	return normalize(vec3(-0.45 + 0.25 * cos(a), 0.7 + 0.12 * sin(a), 0.9));
}

vec3 env(vec3 rd) {
	vec3 c = mix(AMB0, AMB1, smoothstep(-0.2, 0.9, rd.y));
	c += smoothstep(0.86, 0.98, dot(rd, keyDir())) * vec3(1.4, 1.5, 1.6);
	c += smoothstep(0.9, 0.99, dot(rd, normalize(vec3(0.8, -0.3, 0.5)))) * GLOW * 0.4;
	return c;
}

// A pulse racing up through the edges, twice per loop.
float pulse(vec3 lp) {
	return spow(0.5 + 0.5 * sin(TAU * uPhase * 2.0 - (lp.x * 0.45 + lp.y * 0.85) * 8.8 / LOGO_S), 8.0);
}

vec3 shadeLogo(vec3 p, vec3 n, vec3 v) {
	vec3 lp = toLogo(p);
	vec3 ln = logoRot() * n;
	vec2 q = (lp.xy - LOGO_C) / LOGO_S;
	float din = max(-logo2(q) * LOGO_S, 0.0);
	vec3 refl = env(reflect(-v, n));
	float F = fresnel(n, v, 0.05);
	float pl = pulse(lp);
	float face = abs(ln.z);
	vec3 col;
	if (face > 0.97) {
#ifdef LOGO_FACE
		col = logoFace(lp, n, v, din, pl);
#else
		float g = clamp((lp.y - LOGO_C.y) / (LOGO_S * 0.45) * 0.5 + 0.5, 0.0, 1.0);
		col = mix(FACE0, FACE1, g * g);
		col *= 0.8 + 0.4 * noise3(vec3(lp.x * 2.0 + lp.y * 2.0, (lp.x - lp.y) * 60.0, 0.0));
		col += refl * F * 0.9 + spow(dot(n, normalize(keyDir() + v)), 300.0) * vec3(2.0);
#endif
		col += exp(-(din - LOGO_CH) * 12.0 * 8.8 / LOGO_S) * GLOW * (0.16 + 0.45 * pl);
		float bx = (lp.x * 0.5 + (lp.y - LOGO_C.y) * 0.5) * 8.8 / LOGO_S - (-8.0 + 16.0 * uPhase);
		col += exp(-bx * bx * 2.5) * mix(GLOW, vec3(1.0), 0.6) * 0.12;
	} else if (face > 0.25) {
		float t = clamp(din / LOGO_CH, 0.0, 1.0);
#ifdef LOGO_EDGE
		col = logoEdge(lp, t, pl) + refl * 0.3;
#else
		col = mix(HOT * 0.85, GLOW * 0.55, smoothstep(0.0, 0.7, t));
		col *= 0.55 + 1.3 * pl;
		col += refl * 0.3;
#endif
	} else {
		col = FACE0 * 2.0 + refl * F + GLOW * 0.25 * (0.5 + pl);
	}
	return col;
}

// ---- faces ----

vec3 kitFrame(vec2 uv, vec3 col) {
	vec2 e2 = min(uv, 1.0 - uv) * vec2(10.0, 16.0);
	float e = min(e2.x, e2.y);
	col = mix(col, mix(vec3(0.01, 0.011, 0.014), vec3(0.085, 0.088, 0.1), smoothstep(0.0, 0.18, e)), 1.0 - smoothstep(0.18, 0.2, e));
	col += (1.0 - smoothstep(0.0, 0.025, abs(e - 0.24))) * GLOW * 0.2;
	return col * (0.7 + 0.3 * smoothstep(0.0, 2.0, e));
}

vec3 outside(vec2 uv) {
	vec2 P = vec2((uv.x - 0.5) * 10.0, (uv.y - 0.5) * 16.0);
	vec3 ro = vec3(0.0, 0.0, 40.0);
	vec3 rd = normalize(vec3(P, 0.0) - ro);
	float t = (ro.z - 2.4) / -rd.z;
	float minLogo = 1e9;
	vec2 hit = vec2(1e9, 0.0);
	for (int i = 0; i < 200; i++) {
		vec3 p = ro + rd * t;
		vec2 m = kitMap(p);
		minLogo = min(minLogo, logoD(p));
		if (m.x < 0.0004) { hit = vec2(t, m.y); break; }
		t += m.x * 0.8;
		if (p.z < -2.4) break;
	}
	float halo = exp(-max(minLogo, 0.0) * 3.0 * 8.8 / LOGO_S);
	vec3 col;
	if (hit.x > 1e8) {
		col = capeBackground(P, halo);
	} else {
		vec3 p = ro + rd * hit.x;
		vec3 n = kitNormal(p);
		col = hit.y < 1.5 ? shadeLogo(p, n, -rd) : shadeExtra(p, n, -rd);
	}
#ifdef OVERLAY
	col = capeOverlay(P, col, hit.x < 1e8 && hit.y < 1.5);
#endif
	return kitFrame(uv, col);
}

vec3 kitInside(vec2 uv) {
	vec2 P = vec2((uv.x - 0.5) * 10.0, (uv.y - 0.5) * 16.0);
#ifdef INSIDE
	vec3 col = capeInside(P, uv);
#else
	vec3 col = FABRIC * (0.8 + 0.4 * fbm3(vec3(P * 16.0, 0.0), 3));
	col *= 0.85 + 0.3 * (0.5 + 0.5 * sin(P.x * 90.0) * sin(P.y * 90.0));
	vec2 lq = (P - vec2(0.0, 0.8)) / 6.0;
	float d = logo2(lq) * 6.0;
	float stitch = (1.0 - smoothstep(0.0, 0.03, abs(d))) * step(0.4, fract(atan(lq.y, lq.x) * 40.0 + length(lq) * 30.0));
	float travel = spow(0.5 + 0.5 * sin(TAU * uPhase - P.y * 0.4), 6.0);
	col += stitch * GLOW * (0.06 + 0.2 * travel);
#endif
	vec2 e2 = min(uv, 1.0 - uv) * vec2(10.0, 16.0);
	col += exp(-min(e2.x, e2.y) * 3.5) * GLOW * 0.08;
	return col;
}

vec3 kitEdge(vec2 uv, bool vertical) {
	float across = vertical ? uv.x : uv.y;
	float along = vertical ? uv.y * 16.0 : uv.x * 10.0;
	float cyl = sin(across * PI);
	vec3 col = mix(vec3(0.008, 0.009, 0.012), vec3(0.085, 0.088, 0.1), spow(cyl, 3.0));
	float core = 1.0 - smoothstep(0.0, 0.06, abs(across - 0.5));
	float pl = spow(0.5 + 0.5 * sin(TAU * uPhase * 2.0 - along * 0.85), 10.0);
	col += core * GLOW * (0.18 + 1.3 * pl);
	return col;
}

#ifndef CUSTOM_COLOR
vec3 capeColor(vec2 uv, int face) {
	if (face == 0) return outside(uv);
	if (face == 1) return kitInside(uv);
	return kitEdge(uv, face == 2 || face == 3);
}
#endif
