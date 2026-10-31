// Shared by the storm capes (Thunderstrike, Crimson Storm): rolling clouds, rain, forked lightning behind the logo
// that lights up the clouds - two strikes per loop. Needs GLOW / HOT / STORM_DARK / STORM_LIT from the cape.

float extraD(vec3 p) { return 1e9; }
vec3 shadeExtra(vec3 p, vec3 n, vec3 v) { return vec3(0.0); }

// Flash envelope for a strike at loop time t0: a sharp hit, a flicker, a fade.
float strike(float t0) {
	float t = fract(uPhase - t0);
	return exp(-t * 16.0) * (0.75 + 0.25 * cos(t * 90.0)) + exp(-pow2((t - 0.06) * 40.0)) * 0.6;
}

float flashNow() { return max(strike(0.18), strike(0.64) * 0.85); }

vec3 capeBackground(vec2 P, float halo) {
	float f1 = strike(0.18);
	float f2 = strike(0.64) * 0.85;
	float flash = max(f1, f2);
	// Clouds drifting in a loop; lit from inside by each flash.
	float n = fbm3(vec3(P * vec2(0.22, 0.3), 0.0) + loopOffset(0.6, 1, 0.0), 6);
	float n2 = fbm3(vec3(P * 0.6 + 4.0, 1.0) + loopOffset(0.5, 1, 0.4), 5);
	float cloud = smoothstep(0.42, 0.85, n) * smoothstep(-9.0, 4.0, P.y);
	vec3 c = mix(vec3(0.002, 0.002, 0.004), STORM_DARK, cloud);
	c += STORM_LIT * spow(cloud, 1.5) * (0.03 + 0.9 * flash * (0.4 + n2));
	// The bolts, each a fresh shape: from the top down past the logo.
	c += bolt(P, vec2(-1.8, 8.5), vec2(1.4, -6.0), 3.0, 0.05) * f1 * HOT * 1.4;
	c += bolt(P, vec2(-0.6, 3.0), vec2(-4.2, -2.5), 7.0, 0.03) * f1 * HOT * 0.7;
	c += bolt(P, vec2(2.6, 8.5), vec2(-1.2, -7.0), 11.0, 0.05) * f2 * HOT * 1.3;
	c += bolt(P, vec2(1.4, 2.0), vec2(4.4, -3.5), 13.0, 0.03) * f2 * HOT * 0.6;
	c += halo * GLOW * (0.1 + 0.5 * flash);
	return c;
}

vec3 capeOverlay(vec2 P, vec3 col, bool hitLogo) {
	// Rain: thin slanted streaks, three cells of fall per loop, over everything.
	vec2 g = vec2(P.x * 3.0 + P.y * 0.5, P.y * 0.55 + uPhase * 3.0);
	vec2 cell = floor(g);
	vec2 f = fract(g);
	float h = hash21(cell);
	float streak = (1.0 - smoothstep(0.0, 0.035, abs(f.x - h))) * smoothstep(0.0, 0.25, f.y) * (1.0 - smoothstep(0.25, 0.7, f.y)) * step(0.55, h);
	float flash = flashNow();
	col += streak * (vec3(0.05, 0.05, 0.07) + STORM_LIT * flash * 0.5);
	// The flash lights the logo's face too.
	if (hitLogo) col += STORM_LIT * flash * 0.06;
	return col;
}
