#version 330

// Velo Shaders - the procedural sky: colour gradient, sunsets, sun, phased moon, stars, aurora.
// Vanilla only (needs VeloPack2/VeloPack3 from minecraft:globals.glsl); import velo_lib first.

const float VELO_TAU = 6.2831853;

float velo_sun_size() { return velo_byte(VeloPack2.y, 1); }
float velo_sun_glow() { return velo_byte(VeloPack2.y, 2); }
float velo_moon_size() { return velo_byte(VeloPack2.y, 3); }
float velo_star_brightness() { return velo_byte(VeloPack2.z, 0); }
float velo_star_density() { return velo_byte(VeloPack2.z, 1); }
float velo_aurora() { return velo_byte(VeloPack2.z, 2); }
int velo_moon_phase() { return int((VeloPack2.z >> 24u) & 7u); }
float velo_fog_sky_blend() { return velo_byte(VeloPack2.w, 0); }
float velo_sunset_intensity() { return velo_byte(VeloPack2.w, 1); }
float velo_milky_way() { return velo_byte(VeloPack4.x, 0); }
float velo_shooting_stars() { return velo_byte(VeloPack4.x, 1); }

float velo_hash(vec3 p) {
    p = fract(p * vec3(443.897, 441.423, 437.195));
    p += dot(p, p.yzx + 19.19);
    return fract((p.x + p.y) * p.z);
}

float velo_hash2(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float velo_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(velo_hash2(i), velo_hash2(i + vec2(1.0, 0.0)), u.x),
            mix(velo_hash2(i + vec2(0.0, 1.0)), velo_hash2(i + vec2(1.0, 1.0)), u.x), u.y);
}

float velo_fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += velo_noise(p) * a;
        p = p * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

// The night sky turns with the sun (Minecraft's stars rotate about the Z axis).
vec3 velo_sky_rotate(vec3 dir) {
    float c = VeloSun.y;
    float s = -VeloSun.x;
    return vec3(c * dir.x + s * dir.y, -s * dir.x + c * dir.y, dir.z);
}

// A colour setting applied as a tint that keeps the vanilla colour's brightness (so day/night
// still work): amount 0 = vanilla, 1 = the chosen hue at vanilla's brightness.
vec3 velo_tinted(vec3 vanilla, uint setting) {
    vec3 hue = velo_rgb(setting);
    float amount = velo_byte(setting, 3);
    vec3 target = hue * (velo_luma(vanilla) / max(velo_luma(hue), 0.02));
    return mix(vanilla, target, amount);
}

vec3 velo_moon(vec3 dir, vec3 moonDir, float radius, out float coverage) {
    coverage = 0.0;
    // The moon travels in the X/Y plane, so Z is always perpendicular to it.
    vec3 right = normalize(cross(moonDir, vec3(0.0, 0.0, 1.0)));
    vec3 up = cross(right, moonDir);
    vec2 uv = vec2(dot(dir, right), dot(dir, up)) / sin(radius);
    float r = length(uv);
    if (r >= 1.0 || dot(dir, moonDir) < 0.0) {
        return vec3(0.0);
    }
    vec3 sphere = vec3(uv, sqrt(1.0 - r * r));
    // Phase 0 is a full moon; the terminator sweeps round over the eight phases.
    float phase = float(velo_moon_phase()) / 8.0 * VELO_TAU;
    vec3 sunSide = vec3(sin(phase), 0.0, cos(phase));
    float lit = smoothstep(-0.06, 0.12, dot(sphere, sunSide));
    float maria = velo_fbm(uv * 2.2 + 3.0);
    float craters = velo_fbm(uv * 7.0 + 11.0);
    float surface = 0.62 + 0.25 * smoothstep(0.35, 0.65, maria) + 0.13 * craters;
    float limb = mix(0.75, 1.0, sphere.z);
    coverage = smoothstep(1.0, 0.95, r);
    return vec3(0.93, 0.95, 1.0) * surface * limb * (lit * 1.25 + 0.035);
}

vec3 velo_stars(vec3 dir, float night) {
    float brightness = velo_star_brightness();
    if (brightness <= 0.0 || night <= 0.0) {
        return vec3(0.0);
    }
    vec3 d = velo_sky_rotate(dir);
    float res = mix(90.0, 220.0, velo_star_density());
    vec3 p = d * res;
    vec3 cell = floor(p);
    float h = velo_hash(cell);
    float threshold = 1.0 - mix(0.025, 0.09, velo_star_density());
    if (h < threshold) {
        return vec3(0.0);
    }
    vec3 center = cell + 0.5 + (vec3(velo_hash(cell + 1.3), velo_hash(cell + 2.7), velo_hash(cell + 4.1)) - 0.5) * 0.6;
    float dist = length(p - center);
    float size = mix(0.12, 0.28, fract(h * 37.0));
    float star = smoothstep(size, 0.0, dist);
    float twinkle = 0.65 + 0.35 * sin(VeloCam.w * (2.0 + fract(h * 91.0) * 3.0) + h * 400.0);
    float hue = fract(h * 13.0);
    vec3 tint = hue < 0.5 ? mix(vec3(0.62, 0.75, 1.0), vec3(1.0), hue * 2.0) : mix(vec3(1.0), vec3(1.0, 0.72, 0.5), hue * 2.0 - 1.0);
    return tint * star * twinkle * brightness * night * 2.8;
}


// The Milky Way: a glowing band across the sky with coloured nebula clouds, dark dust lanes and a
// dense sprinkle of faint stars.
vec3 velo_milky_way_glow(vec3 dir, float night) {
    float strength = velo_milky_way();
    if (strength <= 0.0 || night <= 0.0) {
        return vec3(0.0);
    }
    vec3 d = velo_sky_rotate(dir);
    // the band's plane, tilted against the sky's axis
    vec3 bandNormal = normalize(vec3(0.35, 0.25, 0.9));
    float h = dot(d, bandNormal);
    vec3 t = normalize(cross(bandNormal, vec3(0.0, 1.0, 0.0)));
    vec2 uv = vec2(atan(dot(d, t), dot(d, cross(bandNormal, t))), h) * vec2(2.2, 7.0);
    float core = exp(-h * h * 40.0);
    float wide = exp(-h * h * 9.0);
    float clouds = velo_fbm(uv * 1.3 + 4.0);
    float dust = smoothstep(0.45, 0.75, velo_fbm(uv * 2.6 + 17.0)) * exp(-h * h * 60.0);
    vec3 nebula = mix(vec3(0.26, 0.36, 0.78), vec3(0.62, 0.42, 0.78), velo_fbm(uv * 0.8 + 9.0) * 0.7);
    nebula = mix(nebula, vec3(1.0, 0.82, 0.62), core * 0.5);
    vec3 glow = nebula * (wide * 0.35 + core * 0.9) * (0.4 + clouds * 1.1) * (1.0 - dust * 0.85);
    // dense faint stars inside the band
    vec3 p = d * 420.0;
    float h2 = velo_hash(floor(p));
    float fine = step(0.985, h2) * smoothstep(0.35, 0.0, length(fract(p) - 0.5)) * wide;
    return (glow * 0.4 + vec3(0.85, 0.9, 1.0) * fine * 1.2) * strength * night;
}

// Now and then a meteor streaks across: one time slot every few seconds, a random path in each.
vec3 velo_shooting_star(vec3 dir, float night) {
    float amount = velo_shooting_stars();
    if (amount <= 0.0 || night <= 0.2 || dir.y < 0.05) {
        return vec3(0.0);
    }
    float period = mix(14.0, 4.0, amount);
    float slot = floor(VeloCam.w / period);
    float local = fract(VeloCam.w / period) * period;
    float duration = 0.9;
    if (local > duration || velo_hash(vec3(slot, 3.0, 7.0)) > 0.75) {
        return vec3(0.0);
    }
    float az = velo_hash(vec3(slot, 1.0, 2.0)) * VELO_TAU;
    float el = mix(0.35, 1.0, velo_hash(vec3(slot, 5.0, 1.0)));
    vec3 start = normalize(vec3(cos(az) * cos(el), sin(el), sin(az) * cos(el)));
    vec3 side = normalize(cross(start, vec3(0.0, 1.0, 0.0)));
    vec3 travel = normalize(side * (velo_hash(vec3(slot, 9.0, 4.0)) > 0.5 ? 1.0 : -1.0) - start * 0.15 + vec3(0.0, -0.35, 0.0));
    float progress = local / duration;
    vec3 head = normalize(start + travel * progress * 0.55);
    // distance from the trail segment behind the head
    vec3 tail = normalize(head - travel * 0.12);
    vec3 ab = tail - head;
    float k = clamp(dot(dir - head, ab) / dot(ab, ab), 0.0, 1.0);
    float dist = length(dir - (head + ab * k));
    float trail = smoothstep(0.0025, 0.0, dist) * (1.0 - k);
    float fade = smoothstep(0.0, 0.15, progress) * smoothstep(1.0, 0.7, progress);
    return vec3(0.85, 0.92, 1.0) * trail * fade * 2.5 * night;
}

vec3 velo_aurora_glow(vec3 dir, float night) {
    float strength = velo_aurora();
    if (strength <= 0.0 || night <= 0.0 || dir.y <= 0.02) {
        return vec3(0.0);
    }
    vec2 p = dir.xz / (dir.y + 0.18);
    float t = VeloCam.w * 0.03;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 3; i++) {
        float layer = float(i);
        vec2 q = p * (0.55 + layer * 0.12) + vec2(t * (1.0 + layer * 0.3), layer * 3.1);
        float band = velo_fbm(vec2(q.x * 1.3, q.y * 0.35 + velo_noise(q * 0.7 + t) * 1.5));
        float curtain = smoothstep(0.55, 0.85, band) * smoothstep(0.02, 0.25, dir.y) * smoothstep(0.95, 0.4, dir.y);
        vec3 col = mix(vec3(0.15, 1.0, 0.55), vec3(0.55, 0.3, 1.0), layer / 2.0 + band * 0.25);
        sum += col * curtain;
    }
    return sum * strength * night * 0.55;
}

// dir: view direction (world). vanillaSky: vanilla's sky colour (zenith); fog: its horizon/fog colour.
vec3 velo_sky(vec3 dir, vec3 vanillaSky, vec3 fog) {
    float day = VeloSun.w;
    float night = 1.0 - day;
    float rain = VeloSky.a;
    vec3 sunDir = velo_sun_dir();
    float up = clamp(dir.y, 0.0, 1.0);

    // a deeper, more saturated zenith than vanilla's
    vec3 zenith = velo_tinted(vanillaSky, VeloPack3.x);
    zenith = mix(vec3(velo_luma(zenith)), zenith, 1.15) * 0.84;
    vec3 horizon = velo_tinted(mix(fog, vanillaSky, 0.3), VeloPack3.y) * 0.9;
    vec3 color = mix(horizon, zenith, pow(up, 0.45));

    // Sunrise/sunset: a warm band along the horizon, strongest towards the sun.
    float mu = dot(dir, sunDir);
    float sunLow = 1.0 - smoothstep(0.0, 0.38, abs(sunDir.y + 0.04));
    float band = pow(1.0 - up, 3.5);
    vec3 sunsetColor = velo_rgb(VeloPack3.z);
    float towardSun = 0.3 + 0.7 * pow(mu * 0.5 + 0.5, 3.0);
    color = mix(color, sunsetColor * mix(0.55, 1.0, day), clamp(sunLow * band * towardSun * velo_sunset_intensity() * (1.0 - rain * 0.7), 0.0, 1.0));

    // Sun: disc with a bright core and glow.
    float sunRadius = mix(0.018, 0.075, velo_sun_size());
    float disc = smoothstep(cos(sunRadius), cos(sunRadius * 0.8), mu);
    float glowAmount = velo_sun_glow();
    float glow = pow(max(mu, 0.0), mix(500.0, 40.0, glowAmount)) * glowAmount * 0.38
            + pow(max(mu, 0.0), 5.0) * glowAmount * 0.05 * (0.4 + sunLow);
    vec3 sunColor = mix(vec3(1.0, 0.96, 0.88), sunsetColor, sunLow * 0.65);
    float aboveHorizon = smoothstep(-0.04, 0.03, dir.y);
    color += sunColor * (disc * 1.6 + glow) * aboveHorizon * (1.0 - rain * 0.85);

    // Night sky: Milky Way, stars, meteors and aurora behind the moon.
    float clearNight = night * aboveHorizon * (1.0 - rain);
    color += velo_milky_way_glow(dir, clearNight) + velo_stars(dir, clearNight) + velo_shooting_star(dir, clearNight)
            + velo_aurora_glow(dir, night * (1.0 - rain));

    // Moon opposite the sun.
    vec3 moonDir = -sunDir;
    float moonRadius = mix(0.014, 0.06, velo_moon_size());
    float coverage;
    vec3 moon = velo_moon(dir, moonDir, moonRadius, coverage);
    color = mix(color, moon, coverage * aboveHorizon * (1.0 - rain * 0.8));
    float moonHalo = max(dot(dir, moonDir), 0.0);
    color += vec3(0.55, 0.65, 1.0) * (pow(moonHalo, 180.0) * 0.22 + pow(moonHalo, 24.0) * 0.06) * night * (1.0 - rain * 0.6);

    // Optionally let the horizon fade into the (vanilla) fog colour.
    return mix(color, fog, velo_fog_sky_blend() * pow(1.0 - up, 6.0) * 0.6);
}
