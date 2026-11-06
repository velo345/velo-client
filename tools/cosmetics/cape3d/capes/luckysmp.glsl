// LuckySMP partner cloak: their logo - chunky gold "LUCKY" with a deep maroon extrusion and dark outline, chrome "SMP"
// with a navy one - over a dark purple-gold background, with lucky blocks (gold, lavender pixel "?", corner studs)
// falling in two depths round it. Loops cleanly: every block wraps while it is off the cloak.
// @pixeltext "LUCKY" "SMP"
uniform sampler2D uText2;

const vec3 GOLD_HI = vec3(0.98, 0.76, 0.13);
const vec3 GOLD_LO = vec3(0.88, 0.30, 0.015);
const vec3 AMBER_SIDE = vec3(0.24, 0.055, 0.01);
const vec3 MAROON_HI = vec3(0.40, 0.075, 0.03);
const vec3 MAROON_LO = vec3(0.11, 0.018, 0.01);
const vec3 NAVY_HI = vec3(0.22, 0.24, 0.38);
const vec3 NAVY_LO = vec3(0.04, 0.045, 0.10);
const vec3 LAVENDER = vec3(0.84, 0.76, 1.02);
const vec3 PURPLE_DARK = vec3(0.09, 0.025, 0.15);
const vec3 WARM = vec3(1.0, 0.55, 0.12);

const float TEXT_S = 17.0;
const vec2 LUCKY_C = vec2(-0.1, 1.3);
const vec2 SMP_C = vec2(-0.1, -1.25);
const int NBLOCKS = 9;
const int NNUG = 0;
// letters: a gold/chrome face at FACE_Z, extruded back and down-right (SHEAR per unit of depth) like their logo
const float FACE_Z = 0.35;
const float DEPTH = 1.0;
const vec2 SHEAR = vec2(0.17, -0.42);
const float TEXT_CH = 0.09;
const float OUTLINE_W = 0.19;

// ---- wordmarks ----

float word2(sampler2D tex, vec2 q) {
	vec2 uv = vec2(q.x + 0.5, 0.5 - q.y * 8.0);
	vec2 c = clamp(uv, 0.0, 1.0);
	return texture(tex, c).r + length((uv - c) * vec2(1.0, 0.125));
}

mat3 textRot() { return rotY(0.10 * sin(TAU * uPhase)) * rotX(0.05 * cos(TAU * uPhase)); }

// the word's own space; Lucky Burst lets a hop run through the letters once per loop
vec3 wordQ(vec3 p, vec2 c, float delay) {
	vec3 q = textRot() * (p - vec3(c, 0.0));
	q.y -= 0.12 * sin(TAU * uPhase);
	return q;
}

float extDepth(vec3 q) { return clamp(FACE_Z - q.z, 0.0, DEPTH); }
float sharpText(sampler2D tex, vec3 q) {
	float d2 = word2(tex, (q.xy - SHEAR * extDepth(q)) / TEXT_S) * TEXT_S;
	float dz = max(q.z - FACE_Z, FACE_Z - DEPTH - q.z);
	return max(max(d2, dz), (d2 + q.z - FACE_Z + TEXT_CH) * 0.70710678) * 0.85;
}
// the thin dark outline round face and extrusion: both silhouettes, grown, on a slab just behind the letters
float outlineSlab(sampler2D tex, vec3 q) {
	float a = word2(tex, q.xy / TEXT_S) * TEXT_S;
	float b = word2(tex, (q.xy - SHEAR * DEPTH) / TEXT_S) * TEXT_S;
	float c = word2(tex, (q.xy - SHEAR * DEPTH * 0.5) / TEXT_S) * TEXT_S;
	return max(min(min(a, b), c) - OUTLINE_W, abs(q.z - (FACE_Z - DEPTH - 0.1)) - 0.08);
}

// ---- lucky blocks and nuggets ----

float sdRoundBox(vec3 p, vec3 b, float r) { vec3 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r; }

void blockPose(int i, out vec3 c, out float h, out mat3 R) {
	// falling in two depths: behind the logo (bigger, dimmer) and in front (small, at the sides)
	float hh = hash11(float(i) * 4.71 + 0.3);
	bool front = i % 3 == 2;
	float speed = (i % 2 == 0) ? 1.0 : 2.0;
	float lane = (float(i) + 0.5) / float(NBLOCKS);
	float x = mix(-4.0, 4.0, fract(lane * 4.0 + 0.11));
	if (front) x = (i % 2 == 0 ? -1.0 : 1.0) * (3.95 + 0.15 * hh);
	float life = fract(uPhase * speed + hh);          // wraps while the block is off the cloak, so the loop is clean
	c = vec3(x, 9.6 - 19.2 * life, front ? 1.6 : -2.4 - 1.4 * hh);
	h = front ? 0.42 : 0.48 + 0.3 * hh;
	R = rotX(0.35) * rotZ((hh - 0.5) * 0.6) * rotY(hh * 6.0 + 1.6 * life * (hh > 0.5 ? 1.0 : -1.0));
}

void nuggetPose(int i, out vec3 c, out float s, out mat3 R) {
	float h = hash11(float(i) * 5.13 + 1.0);
	R = rotX(TAU * uPhase * 2.0 + h * 6.0) * rotZ(h * 5.0 + TAU * uPhase * 2.0);
	// three nuggets sitting round each flanking block's corners, bobbing and spinning in place
	float sd = i < 3 ? -1.0 : 1.0;
	int k = i % 3;
	vec2 off[3] = vec2[3](vec2(-0.85, 0.85), vec2(0.45, -0.98), vec2(-0.95, -0.72));
	c = vec3(sd * 3.55 + sd * off[k].x, SMP_C.y + off[k].y + 0.12 * sin(TAU * uPhase + float(i)), -0.2 + 0.3 * float(k));
	s = 0.85;
	R = rotX(0.6 + h) * rotY(TAU * uPhase * (k == 1 ? -1.0 : 1.0) + h * 6.0);
}

vec2 map(vec3 p) {
	vec2 best = vec2(sharpText(uText, wordQ(p, LUCKY_C, 0.0)), 1.0);
	float d = sharpText(uText2, wordQ(p, SMP_C, 0.12));
	if (d < best.x) best = vec2(d, 2.0);
	d = outlineSlab(uText, wordQ(p, LUCKY_C, 0.0));
	if (d < best.x) best = vec2(d, 6.0);
	d = outlineSlab(uText2, wordQ(p, SMP_C, 0.12));
	if (d < best.x) best = vec2(d, 7.0);
	for (int i = 0; i < NBLOCKS; i++) {
		vec3 c; float h; mat3 R;
		blockPose(i, c, h, R);
		vec3 lp = R * (p - c);
		d = sdRoundBox(lp, vec3(h), h * 0.1);
		if (d < best.x) best = vec2(d, 3.0 + float(i) * 0.01);
	}
	for (int i = 0; i < NNUG; i++) {
		vec3 c; float s; mat3 R;
		nuggetPose(i, c, s, R);
		if (s < 0.01) continue;
		d = sdRoundBox(R * (p - c), vec3(0.24, 0.15, 0.13) * s, 0.04 * s);
		if (d < best.x) best = vec2(d, 5.0);
	}
	return best;
}

vec3 calcNormal(vec3 p) {
	const vec2 e = vec2(0.0008, -0.0008);
	return normalize(e.xyy * map(p + e.xyy).x + e.yyx * map(p + e.yyx).x + e.yxy * map(p + e.yxy).x + e.xxx * map(p + e.xxx).x);
}

vec3 keyDir() { float a = TAU * uPhase; return normalize(vec3(-0.5 + 0.2 * cos(a), 0.75, 0.85)); }

vec3 env(vec3 rd) {
	vec3 c = mix(vec3(0.03, 0.012, 0.003), vec3(0.18, 0.09, 0.02), smoothstep(-0.3, 0.9, rd.y));
	c += smoothstep(0.84, 0.98, dot(rd, keyDir())) * vec3(2.0, 1.7, 1.3);
	c += smoothstep(0.88, 0.99, dot(rd, normalize(vec3(0.85, -0.2, 0.5)))) * WARM * 0.7;
	return c;
}

// ---- the "?" (5 x 7 pixels, rows from the top, bit 4 = left column) ----

const int QW = 5, QH = 7;
float qPixel(int x, int y) {
	int rows[7] = int[7](14, 27, 3, 6, 12, 0, 12);
	if (x < 0 || y < 0 || x >= QW || y >= QH) return 0.0;
	return float((rows[y] >> (QW - 1 - x)) & 1);
}
// signed distance (in pixels) from f (pixel units, origin at the glyph's top-left) to the lit pixels
float qDist(vec2 f) {
	float d = 1e9;
	for (int y = 0; y < QH; y++) for (int x = 0; x < QW; x++) {
		if (qPixel(x, y) < 0.5) continue;
		vec2 q = abs(f - vec2(float(x) + 0.5, float(y) + 0.5)) - 0.5;
		d = min(d, length(max(q, 0.0)) + min(max(q.x, q.y), 0.0));
	}
	return d;
}

vec3 blockColor(vec3 lp, vec3 ln, float h, float glow) {
	vec3 a = abs(ln);
	vec2 uv;   // face coordinates, never mirrored when the face is looked at from outside
	if (a.x > a.y && a.x > a.z) uv = vec2(-sign(ln.x) * lp.z, lp.y);
	else if (a.y > a.z) uv = vec2(lp.x, -sign(ln.y) * lp.z);
	else uv = vec2(sign(ln.z) * lp.x, lp.y);
	uv /= h;
	float rim = max(abs(uv.x), abs(uv.y));
	vec3 col = mix(GOLD_LO, GOLD_HI, 0.5 + 0.5 * uv.y);
	col *= 0.9 + 0.2 * noise3(vec3(uv * 9.0, 2.0));
	col = mix(col, GOLD_LO * 0.55, smoothstep(0.80, 0.84, rim));
	if (abs(uv.x) > 0.74 && abs(uv.y) > 0.74 && rim < 0.93) col = LAVENDER * 0.95;
	vec2 f = vec2((uv.x + 0.62) / 1.24 * float(QW), (0.66 - uv.y) / 1.32 * float(QH));
	float qd = qDist(f);
	if (qd < 0.0) col = LAVENDER * (1.0 + 0.5 * glow);
	else if (qd < 0.32) col = PURPLE_DARK;
	return col;
}

vec3 shade(vec3 p, vec3 n, vec3 v, float id) {
	vec3 L = keyDir();
	vec3 hv = normalize(L + v);
	vec3 refl = env(reflect(-v, n));
	float diff = 0.4 + 0.6 * max(dot(n, L), 0.0);
	float spec = pow(1e-6 + max(dot(n, hv), 0.0), 90.0);
	float F = fresnel(n, v, 0.06);
	if (id < 2.5) {
		bool gold = id < 1.5;
		vec3 q = wordQ(p, gold ? LUCKY_C : SMP_C, gold ? 0.0 : 0.12);
		vec3 qn = textRot() * n;
		float face = abs(qn.z);
		float din = max(-word2(gold ? uText : uText2, q.xy / TEXT_S) * TEXT_S, 0.0);
		vec3 col;
		if (face > 0.97 && q.z > FACE_Z - 0.02) {
			float g = clamp(q.y / 1.75 + 0.5, 0.0, 1.0);
			col = gold ? mix(GOLD_LO, GOLD_HI, smoothstep(0.1, 0.85, g)) : mix(vec3(0.62, 0.64, 0.72), vec3(1.3, 1.32, 1.38), smoothstep(0.0, 0.7, g));
			col *= diff;
			col += refl * F * (gold ? 0.35 : 0.7) + spec * vec3(0.5);
			float sx = q.x * 0.55 + q.y * 0.9 - (-9.0 + 18.0 * uPhase);
			col += exp(-sx * sx * 2.2) * (gold ? vec3(1.0, 0.85, 0.45) : vec3(0.9)) * 0.5;
		} else if (q.z > FACE_Z - TEXT_CH - 0.02) {
			col = gold ? vec3(1.25, 0.95, 0.35) : vec3(1.35);   // the bright bevel
			col += refl * 0.15;
		} else {
			// the extrusion: lit near the face, deep at the back, lighter on the faces turned down
			float t = clamp((FACE_Z - q.z) / DEPTH, 0.0, 1.0);
			vec3 hi = gold ? MAROON_HI : NAVY_HI, lo = gold ? MAROON_LO : NAVY_LO;
			col = mix(hi, lo, smoothstep(0.0, 0.9, t)) * (0.75 + 0.45 * max(-qn.y, 0.0) + 0.2 * max(qn.x, 0.0));
			col += refl * F * 0.3;
		}
		return col;
	}
	if (id > 5.5) {
		// the outline: dark, with a faint warm lip catching the light
		bool gold = id < 6.5;
		return gold ? vec3(0.07, 0.014, 0.008) : vec3(0.02, 0.02, 0.04);
	}
	if (id < 4.5) {
		int i = int(floor((id - 3.0) * 100.0 + 0.5));
		vec3 c; float h; mat3 R;
		blockPose(i, c, h, R);
		vec3 lp = R * (p - c);
		float glow = 0.5 + 0.5 * sin(TAU * (uPhase * 2.0 + float(i) * 0.3));
		vec3 col = blockColor(lp, R * n, h, glow) * diff;
		col += refl * F * 0.45 + pow(1e-6 + max(dot(n, hv), 0.0), 400.0) * vec3(0.5);   // flat faces: no full-face flash
		col *= p.z < 0.0 ? 0.55 + 0.1 * (p.z + 3.4) : 1.0;   // the far ones sink into the dark
		return col;
	}
	return mix(GOLD_LO, GOLD_HI, 0.5 + 0.5 * n.y) * diff + refl * F + spec * vec3(1.6);
}

// ---- background ----

vec2 rayCentre() {
	return vec2(0.0, 0.0);
}

vec3 background(vec2 P, float nearLogo) {
	vec2 c = P - rayCentre();
	float r = length(c);
	vec3 col = mix(vec3(0.045, 0.018, 0.06), vec3(0.008, 0.003, 0.014), smoothstep(0.0, 11.0, length(P)));
	float a = atan(c.y, c.x) + TAU / 12.0 * uPhase;
	float ray = pow(1e-6 + 0.5 + 0.5 * cos(a * 12.0), 6.0);
	col += ray * WARM * 0.05 * exp(-r * 0.22);
	col += exp(-r * 0.55) * WARM * 0.11;
	col += exp(-max(nearLogo, 0.0) * 2.5) * WARM * 0.07;
	// faint "?" pattern in place, a soft wave of light running up it once per loop
	vec2 g = P * 0.55;
	vec2 cell = floor(g);
	vec2 fc = fract(g) - 0.5;
	vec2 f = vec2(fc.x, -fc.y) * vec2(float(QW), float(QH)) * 1.6 + vec2(float(QW), float(QH)) * 0.5;
	float wave = 0.6 + 0.8 * pow(1e-6 + 0.5 + 0.5 * sin(TAU * uPhase - cell.y * 0.7), 3.0);
	if (hash21(cell + 7.7) > 0.55 && qDist(f) < 0.0) col += LAVENDER * 0.5 * 0.018 * wave;
	// golden sparkles twinkling in place (whole twinkles per loop)
	vec2 s = P * 1.3;
	vec2 sc = floor(s);
	vec2 hh = hash22(sc);
	vec2 sf = fract(s) - (0.2 + 0.6 * hh);
	float tw = pow(1e-6 + 0.5 + 0.5 * sin(TAU * (uPhase * (1.0 + floor(hh.y * 3.0)) + hh.x)), 4.0);
	float star = 0.004 / (abs(sf.x * sf.y) + 0.0015) * (1.0 - smoothstep(0.0, 0.35, length(sf)));
	col += step(0.83, hash21(sc + 3.1)) * star * tw * vec3(1.6, 1.2, 0.5) * 0.12;
	return col;
}

vec3 outside(vec2 uv) {
	vec2 P = vec2((uv.x - 0.5) * 10.0, (uv.y - 0.5) * 16.0);
	vec3 ro = vec3(0.0, 0.0, 40.0);
	vec3 rd = normalize(vec3(P, 0.0) - ro);
	float t = (ro.z - 3.0) / -rd.z;
	float nearLogo = 1e9;
	vec2 hit = vec2(1e9, 0.0);
	for (int i = 0; i < 300; i++) {
		vec3 p = ro + rd * t;
		vec2 m = map(p);
		if (m.y > 5.5) nearLogo = min(nearLogo, m.x);
		if (m.x < 0.0004) { hit = vec2(t, m.y); break; }
		t += m.x * 0.7;
		if (p.z < -4.6) break;
	}
	vec3 col;
	if (hit.x > 1e8) {
		col = background(P, nearLogo);
	} else {
		vec3 p = ro + rd * hit.x;
		col = shade(p, calcNormal(p), -rd, hit.y);
	}
	vec2 e2 = min(uv, 1.0 - uv) * vec2(10.0, 16.0);
	float e = min(e2.x, e2.y);
	col = mix(col, mix(AMBER_SIDE * 0.6, GOLD_LO * 0.8, smoothstep(0.0, 0.16, e)), 1.0 - smoothstep(0.16, 0.19, e));
	col += (1.0 - smoothstep(0.0, 0.03, abs(e - 0.24))) * GOLD_HI * 0.22;
	return col;
}

vec3 inside(vec2 uv) {
	vec2 P = vec2((uv.x - 0.5) * 10.0, (uv.y - 0.5) * 16.0);
	vec3 base = vec3(0.03, 0.012, 0.045);
	vec3 col = base * (0.8 + 0.4 * fbm3(vec3(P * 14.0, 0.0), 3));
	vec2 cell = fract(P * 0.5) - 0.5;
	float quilt = 1.0 - smoothstep(0.0, 0.03, min(abs(cell.x), abs(cell.y)) - 0.47);
	col += quilt * GOLD_LO * 0.05;
	vec2 f = vec2(cell.x, -cell.y) * vec2(float(QW), float(QH)) * 2.6 + vec2(float(QW), float(QH)) * 0.5;
	float pulse = 0.5 + 0.5 * sin(TAU * uPhase - P.y * 0.3);
	if (qDist(f) < 0.0) col += GOLD_HI * (0.05 + 0.08 * pulse);
	return col;
}

vec3 edgeStrip(vec2 uv, bool vertical) {
	float across = vertical ? uv.x : uv.y;
	float along = vertical ? uv.y * 16.0 : uv.x * 10.0;
	vec3 col = mix(AMBER_SIDE * 0.5, GOLD_LO * 0.9, pow(1e-6 + sin(across * PI), 2.0));
	float pl = pow(1e-6 + 0.5 + 0.5 * sin(TAU * uPhase * 2.0 - along * 0.8), 10.0);
	col += (1.0 - smoothstep(0.0, 0.08, abs(across - 0.5))) * GOLD_HI * (0.15 + 0.8 * pl);
	return col;
}

vec3 capeColor(vec2 uv, int face) {
	if (face == 0) return outside(uv);
	if (face == 1) return inside(uv);
	return edgeStrip(uv, face == 2 || face == 3);
}
