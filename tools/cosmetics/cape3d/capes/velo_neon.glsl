// Velo Neon (the free Discord-link cape) - the Velo logo cut with hard chamfers from dark glass, its edges burning white-hot to deep red
// with a pulse of light racing through them, a few sharp shards drifting round it, a dark, quiet background.

const vec2 LOGO_C = vec2(0.0, 0.8);
const float LOGO_S = 8.8;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
const vec3 CYAN = vec3(1.95, 0.075, 0.055);   // the glow colour (deep red)
const vec3 WHITE = vec3(2.4, 1.05, 0.85);      // white-hot edge

mat3 logoRot() { return rotY(0.16 * sin(TAU * uPhase)) * rotX(0.06 * cos(TAU * uPhase)); }
vec3 toLogo(vec3 p) { vec3 c = vec3(LOGO_C, 0.0); return logoRot() * (p - c) + c; }
float logoD(vec3 p) { return sdLogoSharp(toLogo(p), LOGO_C, LOGO_S, LOGO_TH, LOGO_CH); }

// Blade-like shards: thin, long double pyramids at fixed spots, bobbing and turning a little.
const int SHARDS = 6;
vec3 shardPos(int i) {
	vec3 pos[6] = vec3[6](vec3(-4.0, 6.4, -0.6), vec3(3.9, 6.9, 0.4), vec3(4.3, -3.6, -0.4), vec3(-4.2, -3.2, 0.6),
		vec3(-1.6, -6.1, -0.8), vec3(2.2, -6.6, 0.3));
	float b = TAU * (uPhase + float(i) * 0.17);
	return pos[i] + vec3(0.0, 0.18 * sin(b), 0.1 * cos(b));
}
float shardD(vec3 p) {
	float d = 1e9;
	for (int i = 0; i < SHARDS; i++) {
		vec3 q = p - shardPos(i);
		float h = hash11(float(i) * 3.1);
		q = rotZ(h * 6.0 + 0.15 * sin(TAU * uPhase + float(i))) * q;
		q = rotY(h * 3.0 + 0.4 * sin(TAU * uPhase + h * 6.0)) * q;
		vec3 s = vec3(0.2, 0.8 + 0.4 * h, 0.08);
		q = abs(q / s);
		d = min(d, (q.x + q.y + q.z - 1.0) * 0.57735 * min(s.x, min(s.y, s.z)) * 1.6);
	}
	return d;
}

vec2 map(vec3 p) {
	float l = logoD(p);
	float s = shardD(p);
	return l < s ? vec2(l, 1.0) : vec2(s, 2.0);
}

vec3 calcNormal(vec3 p) {
	const vec2 e = vec2(0.0008, -0.0008);
	return normalize(e.xyy * map(p + e.xyy).x + e.yyx * map(p + e.yyx).x + e.yxy * map(p + e.yxy).x + e.xxx * map(p + e.xxx).x);
}

vec3 keyDir() {
	float a = TAU * uPhase;
	return normalize(vec3(-0.45 + 0.25 * cos(a), 0.7 + 0.12 * sin(a), 0.9));
}

vec3 env(vec3 rd) {
	vec3 c = mix(vec3(0.006, 0.002, 0.002), vec3(0.05, 0.012, 0.013), smoothstep(-0.2, 0.9, rd.y));
	c += smoothstep(0.86, 0.98, dot(rd, keyDir())) * vec3(1.4, 1.6, 1.9);
	c += smoothstep(0.9, 0.99, dot(rd, normalize(vec3(0.8, -0.3, 0.5)))) * CYAN * 0.5;
	return c;
}

// A pulse racing up through the edges, twice per loop.
float pulse(vec3 lp) {
	float w = 0.5 + 0.5 * sin(TAU * uPhase * 2.0 - (lp.x * 0.45 + lp.y * 0.85));
	return pow(1e-6 + w, 8.0);
}

vec3 shadeLogo(vec3 p, vec3 n, vec3 v) {
	vec3 lp = toLogo(p);
	vec3 ln = logoRot() * n;
	vec2 q = (lp.xy - LOGO_C) / LOGO_S;
	float din = max(-logo2(q) * LOGO_S, 0.0);
	vec3 L = keyDir();
	vec3 h = normalize(L + v);
	vec3 refl = env(reflect(-v, n));
	float F = fresnel(n, v, 0.05);
	float pl = pulse(lp);
	float face = abs(ln.z);
	vec3 col;
	if (face > 0.97) {
		// The face: dark glass, a cold gradient, the edges' light bleeding in, a light sweep crossing once per loop.
		float g = clamp((lp.y - LOGO_C.y) / 4.0 * 0.5 + 0.5, 0.0, 1.0);
		col = mix(vec3(0.004, 0.001, 0.0015), vec3(0.13, 0.016, 0.02), g * g);
		// Fine brushed grain along the strokes.
		col *= 0.8 + 0.4 * noise3(vec3(lp.x * 2.0 + lp.y * 2.0, (lp.x - lp.y) * 60.0, 0.0));
		col += refl * F * 0.9 + pow(1e-6 + max(dot(n, h), 0.0), 300.0) * vec3(2.0);
		col += exp(-(din - LOGO_CH) * 12.0) * CYAN * (0.16 + 0.45 * pl);
		col += exp(-(din - LOGO_CH) * 3.0) * CYAN * 0.06;
		float bx = lp.x * 0.5 + (lp.y - LOGO_C.y) * 0.5 - (-8.0 + 16.0 * uPhase);
		col += exp(-bx * bx * 2.5) * vec3(0.6, 0.22, 0.2) * 0.45;
	} else if (face > 0.25) {
		// The chamfers: they burn - white at the very edge, ice blue behind it.
		float t = clamp(din / LOGO_CH, 0.0, 1.0);
		col = mix(WHITE * 0.85, CYAN * 0.55, smoothstep(0.0, 0.7, t));
		col *= 0.55 + 1.3 * pl;
		col += refl * 0.3;
	} else {
		// The sides: dark, lit by the chamfer above them.
		col = vec3(0.04, 0.006, 0.008) + refl * F + CYAN * 0.25 * (0.5 + pl);
	}
	return col;
}

vec3 shadeShard(vec3 n, vec3 v) {
	float F = fresnel(n, v, 0.08);
	vec3 col = vec3(0.03, 0.004, 0.006) + env(reflect(-v, n)) * F * 1.4;
	col += pow(1e-6 + 1.0 - abs(dot(n, v)), 2.0) * CYAN * 1.2 + pow(1e-6 + max(dot(n, normalize(keyDir() + v)), 0.0), 80.0) * WHITE;
	return col;
}

vec3 background(vec2 P, float minLogo) {
	vec2 q = P - LOGO_C;
	float r = length(q * vec2(1.0, 0.8));
	vec3 c = mix(vec3(0.026, 0.004, 0.005), vec3(0.004, 0.001, 0.0012), smoothstep(0.0, 8.0, r));
	// A soft glow behind the logo, breathing a little, and the logo's own halo.
	float breathe = 0.85 + 0.15 * sin(TAU * uPhase);
	c += exp(-r * 0.5) * CYAN * 0.05 * breathe;
	c += exp(-max(minLogo, 0.0) * 3.0) * CYAN * 0.12 * breathe;
	// Two faint beams falling behind it.
	float beam = exp(-pow(1e-6 + abs(dot(q, normalize(vec2(1.0, 0.55)))), 2.0) * 0.6) + exp(-pow(1e-6 + abs(dot(q, normalize(vec2(-1.0, 0.75)))), 2.0) * 0.9);
	c += beam * CYAN * 0.012 * smoothstep(9.0, 0.0, r);
	// Very faint haze.
	c += pow(1e-6 + fbm3(vec3(P * 0.25, 0.0) + loopOffset(0.4, 1, 0.0), 5), 3.0) * vec3(0.12, 0.014, 0.018) * 0.6;
	// Sparse motes drifting up, one cell per loop, twinkling.
	vec2 g = vec2(P.x * 1.1, P.y * 1.1 - uPhase);
	vec2 cell = floor(g);
	vec2 hh = hash22(cell);
	vec2 f = fract(g) - (0.2 + 0.6 * hh);
	float tw = 0.5 + 0.5 * sin(TAU * (uPhase * (1.0 + floor(hh.y * 3.0)) + hh.x));
	c += (1.0 - smoothstep(0.0, 0.05, length(f))) * step(0.82, hash21(cell + 3.1)) * mix(CYAN, WHITE, hh.x) * (0.15 + 0.7 * tw) * 0.5;
	return c;
}

vec3 outside(vec2 uv) {
	vec2 P = vec2((uv.x - 0.5) * 10.0, (uv.y - 0.5) * 16.0);
	vec3 ro = vec3(0.0, 0.0, 40.0);
	vec3 rd = normalize(vec3(P, 0.0) - ro);
	float t = (ro.z - 1.6) / -rd.z;
	float minLogo = 1e9;
	vec2 hit = vec2(1e9, 0.0);
	for (int i = 0; i < 200; i++) {
		vec3 p = ro + rd * t;
		vec2 m = map(p);
		minLogo = min(minLogo, logoD(p));
		if (m.x < 0.0004) { hit = vec2(t, m.y); break; }
		t += m.x * 0.8;
		if (p.z < -1.6) break;
	}
	vec3 col;
	if (hit.x > 1e8) {
		col = background(P, minLogo);
	} else {
		vec3 p = ro + rd * hit.x;
		vec3 n = calcNormal(p);
		col = hit.y < 1.5 ? shadeLogo(p, n, -rd) : shadeShard(n, -rd);
	}
	// A thin dark frame with a faint blue inner line.
	vec2 e2 = min(uv, 1.0 - uv) * vec2(10.0, 16.0);
	float e = min(e2.x, e2.y);
	col = mix(col, mix(vec3(0.01, 0.012, 0.02), vec3(0.08, 0.09, 0.11), smoothstep(0.0, 0.18, e)), 1.0 - smoothstep(0.18, 0.2, e));
	col += (1.0 - smoothstep(0.0, 0.025, abs(e - 0.24))) * CYAN * 0.22;
	col *= 0.7 + 0.3 * smoothstep(0.0, 2.0, e);
	return col;
}

// The inside: dark navy fabric, the logo's outline stitched in faint blue.
vec3 inside(vec2 uv) {
	vec2 P = vec2((uv.x - 0.5) * 10.0, (uv.y - 0.5) * 16.0);
	vec3 col = vec3(0.022, 0.007, 0.008) * (0.8 + 0.4 * fbm3(vec3(P * 16.0, 0.0), 3));
	float weave = 0.5 + 0.5 * sin(P.x * 90.0) * sin(P.y * 90.0);
	col *= 0.85 + 0.3 * weave;
	vec2 lq = (P - vec2(0.0, 0.8)) / 6.0;
	float d = logo2(lq) * 6.0;
	float stitch = (1.0 - smoothstep(0.0, 0.03, abs(d))) * step(0.4, fract(atan(lq.y, lq.x) * 40.0 + length(lq) * 30.0));
	float travel = pow(1e-6 + 0.5 + 0.5 * sin(TAU * uPhase - P.y * 0.4), 6.0);
	col += stitch * CYAN * (0.08 + 0.25 * travel);
	vec2 e2 = min(uv, 1.0 - uv) * vec2(10.0, 16.0);
	col += exp(-min(e2.x, e2.y) * 3.5) * CYAN * 0.1;
	return col;
}

// Edges: dark piping with a thin glowing core, the pulse running round.
vec3 edgeStrip(vec2 uv, bool vertical) {
	float across = vertical ? uv.x : uv.y;
	float along = vertical ? uv.y * 16.0 : uv.x * 10.0;
	float cyl = sin(across * PI);
	vec3 col = mix(vec3(0.008, 0.01, 0.016), vec3(0.08, 0.09, 0.11), pow(1e-6 + cyl, 3.0));
	float core = 1.0 - smoothstep(0.0, 0.06, abs(across - 0.5));
	float pl = pow(1e-6 + 0.5 + 0.5 * sin(TAU * uPhase * 2.0 - along * 0.85), 10.0);
	col += core * CYAN * (0.2 + 1.4 * pl);
	return col;
}

vec3 capeColor(vec2 uv, int face) {
	if (face == 0) return outside(uv);
	if (face == 1) return inside(uv);
	return edgeStrip(uv, face == 2 || face == 3);
}
