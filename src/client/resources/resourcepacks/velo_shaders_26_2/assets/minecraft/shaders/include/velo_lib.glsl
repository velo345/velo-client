#version 330

// Velo Shaders - terrain functions shared by vanilla's and Sodium's chunk shaders.
// Needs VeloSun, VeloCam, VeloSky and VeloPack from a globals block (both packs' globals.glsl).
// Vertex shaders #define VELO_VERTEX before importing (the rest uses dFdx/dFdy).
// Settings arrive as 8-bit values packed four to a uint (VeloShadersModule.writeGlobals);
// VeloPack is all zero while the module is off.

const int VELO_WATER = 1;
const int VELO_SKY = 2;
const int VELO_GROUND_FOG = 4;
const int VELO_POST_FOG = 8; // the light pass draws volumetric fog: skip the haze here

float velo_byte(uint word, int index) {
    return float((word >> uint(8 * index)) & 255u) / 255.0;
}

vec3 velo_rgb(uint word) {
    return vec3(velo_byte(word, 0), velo_byte(word, 1), velo_byte(word, 2));
}

float velo_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float velo_noise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(velo_hash12(i), velo_hash12(i + vec2(1.0, 0.0)), u.x),
            mix(velo_hash12(i + vec2(0.0, 1.0)), velo_hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

bool velo_on() {
    return VeloPack != uvec4(0u);
}

bool velo_flag(int bit) {
    return ((VeloPack.w >> 24u) & uint(bit)) != 0u;
}

// Flags byte bits 5-7: how much light-emitting blocks glow (0-1).
float velo_emissive_glow() {
    return float((VeloPack.w >> 29u) & 7u) / 7.0;
}

// Light sources glow: bright texels on blocks that sit at full block light (the torch flame,
// lantern, glowstone, lava, sea lantern...). tex: the raw texture colour.
vec3 velo_emissive(vec3 color, vec3 tex, vec2 light) {
    float glow = velo_emissive_glow();
    if (glow <= 0.0) {
        return color;
    }
    float peak = max(tex.r, max(tex.g, tex.b));
    float mask = smoothstep(0.55, 0.85, peak) * smoothstep(0.86, 0.96, light.x);
    return color + tex * tex * mask * glow * 0.9;
}

float velo_lighting() { return velo_byte(VeloPack.x, 0) * 1.5; }
float velo_sun_warmth() { return velo_byte(VeloPack.x, 1); }
float velo_night_brightness() { return velo_byte(VeloPack.x, 2); }
float velo_side_shading() { return velo_byte(VeloPack.x, 3); }
vec3 velo_block_color() { return velo_rgb(VeloPack.y); }
float velo_block_tint() { return velo_byte(VeloPack.y, 3); }
vec3 velo_water_color() { return velo_rgb(VeloPack.z); }
float velo_water_clarity() { return velo_byte(VeloPack.z, 3); }
float velo_waves() { return velo_byte(VeloPack.w, 0) * 2.0; }
float velo_reflections() { return velo_byte(VeloPack.w, 1); }
float velo_fog_density() { return velo_byte(VeloPack.w, 2); }

float velo_luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Biome water colour is a saturated tint with blue on top; grass and leaves are green, glass and
// ice aren't tinted at all. Vanilla gives the chunk shaders no block ids, so this is the test.
bool velo_water_tint(vec3 tint) {
    float hi = max(tint.r, max(tint.g, tint.b));
    float lo = min(tint.r, min(tint.g, tint.b));
    return hi > 0.02 && (hi - lo) / hi > 0.22 && tint.b >= tint.g && tint.b > tint.r;
}

// Sum of travelling sines: x = height, yz = d/dx, d/dz (analytic, for the normal).
vec3 velo_wave(vec2 p, float t) {
    const vec4 W[6] = vec4[6](
        vec4(0.80, 0.60, 1.10, 0.40),
        vec4(-0.45, 0.89, 1.70, 0.25),
        vec4(0.97, -0.24, 2.90, 0.13),
        vec4(-0.70, -0.71, 4.60, 0.07),
        vec4(0.20, 0.98, 7.30, 0.04),
        vec4(-0.93, 0.37, 11.1, 0.025));
    vec3 r = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        float f = W[i].z;
        float phase = dot(p, W[i].xy) * f + t * sqrt(f) * 1.7;
        r.x += sin(phase) * W[i].w;
        r.yz += W[i].xy * (cos(phase) * W[i].w * f);
    }
    return r;
}

float velo_waving() { return velo_byte(VeloPack.x, 3); }

// Sway for leaves and plants. code comes from the vertex colour's alpha (VeloFoliageSodiumMixin):
// 2 = leaves (rustle in place), 3 = plant tip, 4 = half height of a tall plant; bases stay put.
vec3 velo_sway(vec3 relPos, int code) {
    float amount = velo_waving();
    if (code == 0 || amount <= 0.0) {
        return vec3(0.0);
    }
    vec3 w = relPos + VeloCam.xyz;
    float t = VeloCam.w;
    float storm = 1.0 + VeloSky.a * 0.9;
    if (code == 2) {
        return vec3(sin(t * 1.7 + w.x * 0.6 + w.y * 0.35), sin(t * 2.4 + w.z * 0.7 + w.x * 0.2) * 0.6,
                cos(t * 1.9 + w.z * 0.5 + w.x * 0.25)) * 0.035 * amount * storm;
    }
    float weight = code == 3 ? 1.0 : 0.5;
    float gust = sin(t * 0.9 + dot(w.xz, vec2(0.11, 0.07))) * 0.5 + 0.5;
    vec2 wind = vec2(0.85, 0.4);
    vec2 sway = vec2(sin(t * 2.2 + w.x * 0.9 + w.z * 0.35), cos(t * 1.9 + w.z * 0.8 + w.x * 0.2)) * 0.05
            + wind * (0.03 + gust * 0.09);
    return vec3(sway.x, -abs(sway.x) * 0.25, sway.y) * weight * amount * storm;
}

// Vertex offset for a water surface vertex (top faces only: their y isn't on the block grid).
float velo_water_offset(vec3 relPos, float localY) {
    float f = fract(localY);
    if (f < 0.02 || f > 0.98) {
        return 0.0;
    }
    float h = velo_wave(relPos.xz + VeloCam.xz, VeloCam.w).x;
    return (h - 0.45) * 0.05 * velo_waves();
}

float velo_side_shading_soft(float ndl) {
    return clamp(ndl * 0.5 + 0.5, 0.0, 1.0);
}

vec3 velo_sun_color() {
    float h = clamp(VeloSun.y, 0.0, 1.0);
    vec3 noon = vec3(1.0, 0.97, 0.92);
    vec3 low = vec3(1.0, 0.6, 0.32);
    return mix(noon, mix(noon, low, velo_sun_warmth()), 1.0 - smoothstep(0.0, 0.45, h));
}

// What lights the world: the sun by day, the moon (opposite it) by night.
vec3 velo_light_dir() {
    return VeloSun.y > -0.08 ? VeloSun.xyz : -VeloSun.xyz;
}


// ---- Velo light model --------------------------------------------------------------------------
// The chunk shaders light every texel themselves (instead of vanilla's colourless lightmap):
// ambient sky light + sunlight (moonlight) + block light, each with its own colour per time of day.
// The sunlight share goes out in alpha so the light pass removes exactly that part in shadow -
// torch light inside a shadow stays.

// Sunlight colour through the day: deep orange at the horizon, golden in the morning/evening, warm
// white at noon; cool blue moonlight at night.
vec3 velo_sunlight() {
    float h = VeloSun.y;
    float warmth = velo_sun_warmth();
    vec3 noon = vec3(1.0, 0.93, 0.82);
    vec3 golden = mix(noon, vec3(1.0, 0.70, 0.42), 0.5 + warmth * 0.5);
    vec3 horizon = mix(golden, vec3(1.0, 0.46, 0.20), warmth);
    vec3 sun = mix(horizon, golden, smoothstep(0.0, 0.18, h));
    sun = mix(sun, noon, smoothstep(0.18, 0.55, h));
    vec3 moon = vec3(0.42, 0.55, 0.92) * (0.28 + velo_night_brightness() * 0.35);
    float day = VeloSun.w;
    return mix(moon, sun, day) * (1.0 - VeloSky.a * 0.65);
}

// Light from the open sky: blue by day, lilac at dusk, deep blue at night; grey under rain.
vec3 velo_skylight() {
    float day = VeloSun.w;
    float dusk = 1.0 - smoothstep(0.0, 0.3, abs(VeloSun.y));
    vec3 dayAmbient = vec3(0.50, 0.62, 0.86);
    vec3 duskAmbient = vec3(0.58, 0.50, 0.66);
    vec3 nightAmbient = vec3(0.13, 0.17, 0.32) * (0.55 + velo_night_brightness() * 0.9);
    vec3 ambient = mix(nightAmbient, mix(dayAmbient, duskAmbient, dusk * 0.7), day);
    return mix(ambient, vec3(velo_luma(ambient)) * 0.8, VeloSky.a * 0.6);
}

vec3 velo_blocklight_color() {
    vec3 torch = velo_block_color();
    torch = torch / max(max(torch.r, max(torch.g, torch.b)), 0.01);
    return mix(vec3(1.0, 0.88, 0.76), torch, 0.25 + velo_block_tint() * 0.5);
}

vec3 velo_light_curve(vec3 light) {
    return light / (light + 0.32) * 1.32;
}

// albedo: texel x vertex colour (biome tint, vanilla face shade and AO). light: (block, sky) 0-1.
// ndl: facing towards the sun/moon (-1..1). Returns the lit colour and the sunlight share.
vec4 velo_shade(vec3 albedo, vec2 light, float ndl) {
    float sky = light.y;
    float skyCurve = sky * sky * (3.0 - 2.0 * sky);
    float block = light.x;
    // punchy near the source, long soft tail
    // The main image is 8-bit (no HDR): full sun on a white block lands at ~1.0, never far above.
    float blockCurve = block * block * 0.62 + pow(block, 7.0) * 0.14;
    // By day most light is direct sun, so shade and cast shadows read clearly; at night the sky's
    // ambient keeps its share (nights stay readable).
    float day = VeloSun.w;
    vec3 ambient = velo_skylight() * skyCurve * mix(0.62, 0.34, day) * (0.7 + 0.3 * velo_side_shading_soft(ndl));
    vec3 sun = velo_sunlight() * skyCurve * skyCurve * max(ndl, 0.0) * mix(0.58, 0.88, day);
    vec3 blockLight = velo_blocklight_color() * blockCurve;
    vec3 minimum = vec3(0.045, 0.047, 0.058);
    vec3 total = ambient + sun + blockLight + minimum;
    // Tonemapped light (Solas works in HDR and tonemaps; the image here is 8-bit): dim light is
    // lifted so night and shade stay readable, full sun lands at ~1.0 instead of clipping.
    vec3 mapped = velo_light_curve(total);
    vec3 withoutSun = velo_light_curve(total - sun);
    float sunShare = clamp(1.0 - velo_luma(withoutSun) / max(velo_luma(mapped), 1e-4), 0.0, 1.0);
    return vec4(albedo * mapped, sunShare);
}

#ifndef VELO_VERTEX
// Fragment-only from here (screen derivatives); vertex shaders define VELO_VERTEX before importing.

// Flat face normal from screen derivatives, turned towards the camera (works with either
// backend's clip-space handedness).
vec3 velo_face_normal(vec3 relPos) {
    vec3 n = normalize(cross(dFdx(relPos), dFdy(relPos)));
    return dot(n, relPos) > 0.0 ? -n : n;
}

// light: x = block light, y = sky light, both 0-1.
vec3 velo_light(vec3 color, vec3 n, vec2 light) {
    float strength = velo_lighting();
    if (strength <= 0.0) {
        return color;
    }
    float day = VeloSun.w;
    float sky = light.y * light.y;
    float ndl = dot(n, velo_light_dir());
    float sunVis = sky * (1.0 - VeloSky.a * 0.75) * mix(0.3, 1.0, day);
    // Faces towards the sun (or moon) brighter, faces away a little darker: the light moves over the day.
    float directional = 1.0 + velo_side_shading() * sunVis * (ndl > 0.0 ? 0.32 * ndl : 0.2 * ndl);
    vec3 sunTint = mix(vec3(1.0), velo_sun_color(), sunVis * day);
    float night = (1.0 - day) * sky;
    vec3 nightTint = mix(vec3(1.0), vec3(0.74, 0.84, 1.12) * (0.8 + velo_night_brightness() * 0.8), night);
    vec3 torch = velo_block_color();
    torch = torch / max(max(torch.r, max(torch.g, torch.b)), 0.01);
    float torchShare = clamp(light.x * 1.2 - sky * day, 0.0, 1.0);
    vec3 blockTint = mix(vec3(1.0), torch * 1.15, torchShare * velo_block_tint());
    vec3 factor = directional * sunTint * nightTint * blockTint;
    return color * mix(vec3(1.0), factor, min(strength, 1.0)) * (1.0 + max(strength - 1.0, 0.0) * 0.25);
}

// Distance haze and ground fog on top of vanilla's fog.
vec3 velo_fog(vec3 color, vec3 relPos, vec3 fogColor, float skyLight) {
    float density = velo_fog_density();
    if (density <= 0.0 || velo_flag(VELO_POST_FOG)) {
        return color;
    }
    float dist = length(relPos);
    float f = 1.0 - exp(-dist * density * (0.0022 + VeloSky.a * 0.004));
    if (velo_flag(VELO_GROUND_FOG)) {
        float worldY = VeloCam.y + relPos.y;
        // Thickest in the lowlands, at dawn/dusk and at night.
        float ground = exp(-max(worldY - 62.0, 0.0) * 0.07);
        float timeOfDay = mix(1.0, 0.45, smoothstep(0.1, 0.6, VeloSun.y));
        f = max(f, ground * density * timeOfDay * (1.0 - exp(-dist * 0.018)) * 0.85);
    }
    f *= mix(0.4, 1.0, skyLight);
    return mix(color, fogColor, clamp(f, 0.0, 0.92));
}

vec3 velo_sky_reflection(vec3 dir, vec3 fogColor) {
    float up = clamp(dir.y, 0.0, 1.0);
    vec3 sky = mix(fogColor, VeloSky.rgb, smoothstep(0.0, 0.55, up));
    // a soft sun halo in the reflection
    float mu = max(dot(dir, VeloSun.xyz), 0.0);
    sky += velo_sun_color() * pow(mu, 24.0) * 0.35 * VeloSun.w;
    return sky;
}

// Rain drops on water: rings spreading from random points, each drop restarting on its own clock.
float velo_ripples(vec2 p, float t) {
    vec2 cell = floor(p);
    float total = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 c = cell + vec2(float(x), float(y));
            float h = velo_hash12(c);
            vec2 center = c + vec2(fract(h * 13.1), fract(h * 7.3));
            float phase = fract(t * 0.8 + h);
            float r = phase * 1.1;
            float d = length(p - center);
            total += sin((d - r) * 28.0) * smoothstep(0.1, 0.0, abs(d - r)) * (1.0 - phase);
        }
    }
    return total;
}

// Procedural water. lightColor: the scene light on a white surface here (velo_shade of white),
// alpha: the texel's. Flow comes from the surface's slope (flowing water tilts downhill), falls
// get foam streaks, rain leaves ripples.
vec4 velo_water(float alpha, vec3 lightColor, vec3 relPos, vec3 n0, vec2 light, vec3 fogColor, vec2 flow) {
    vec3 V = normalize(-relPos);
    float dist = length(relPos);
    float t = VeloCam.w;
    vec3 wp = relPos + VeloCam.xyz;
    vec3 N = n0;
    float foam = 0.0;
    bool flowing = dot(flow, flow) > 0.5;
    if (n0.y > 0.2) {
        // Surface. Its normal is taken as straight up: flowing water's quads are bent, and the
        // per-triangle normal jumped along their diagonal. Waves stream along Minecraft's flow.
        vec2 p = wp.xz - flow * t * 2.4;
        vec3 w = velo_wave(p, VeloCam.w);
        vec3 detail = velo_wave(p * 2.7 + vec2(13.0, 7.0), VeloCam.w * 1.35);
        vec2 g = (w.yz + detail.yz * 0.35) * 0.055 * (0.35 + velo_waves());
        if (VeloSky.a > 0.05 && light.y > 0.5) {
            vec2 rp = wp.xz * 1.6;
            float r0 = velo_ripples(rp, t);
            vec2 rg = vec2(velo_ripples(rp + vec2(0.03, 0.0), t) - r0, velo_ripples(rp + vec2(0.0, 0.03), t) - r0) / 0.03;
            g += rg * 0.012 * VeloSky.a;
        }
        g /= 1.0 + dist * 0.025;
        N = normalize(vec3(-g.x, 1.0, -g.y));
        if (flowing) {
            vec2 side = vec2(-flow.y, flow.x);
            float streak = velo_noise2(vec2(dot(wp.xz, flow) * 1.2 - t * 3.0, dot(wp.xz, side) * 2.6));
            foam = smoothstep(0.62, 0.9, streak) * 0.6;
        }
    } else {
        // falling water: white streaks rushing down
        vec2 face = vec2(dot(wp.xz, normalize(vec2(-n0.z, n0.x) + 1e-4)), wp.y);
        float streak = velo_noise2(vec2(face.x * 3.0, face.y * 0.35 + t * 2.4)) * 0.6 + velo_noise2(vec2(face.x * 7.0, face.y * 0.8 + t * 3.8)) * 0.4;
        // most vertical water faces are short edges, not real falls: keep the streaks light
        foam = smoothstep(0.5, 0.85, streak) * 0.45;
    }
    float ndv = clamp(dot(N, V), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - ndv, 5.0);
    float refl = velo_reflections();
    vec3 R = reflect(-V, N);
    vec3 skyRef = velo_sky_reflection(R, fogColor) * mix(0.12, 1.0, light.y);
    float day = VeloSun.w;
    float rl = max(dot(R, velo_light_dir()), 0.0);
    float shine = pow(rl, 700.0) * 7.0 + pow(rl, 70.0) * 0.22;
    vec3 glint = mix(vec3(0.55, 0.65, 0.95) * 0.3, velo_sun_color(), day) * shine * light.y * light.y * (1.0 - VeloSky.a);

    // body: the chosen water colour under the scene's light, deeper looking straight down
    vec3 body = velo_water_color() * lightColor * mix(0.22, 0.42, 1.0 - ndv);
    vec3 color = mix(body, skyRef, fresnel * refl) + glint * refl;
    color = mix(color, lightColor * 0.95, foam * 0.85);

    float clarity = velo_water_clarity();
    float a = mix(alpha * 0.85, alpha * 0.35, clarity);
    a = mix(a, 1.0, max(fresnel * refl, foam * 0.6));
    a = clamp(a + velo_luma(glint) * refl, 0.0, 1.0);
    return vec4(color, a);
}

// Rain: surfaces open to the sky turn darker and glossy; flat ground collects puddles that mirror
// the sky, with raindrop rings.
vec3 velo_wet(vec3 color, vec3 relPos, vec3 n, vec2 light, vec3 fogColor) {
    float wet = VeloSky.a * smoothstep(0.86, 0.97, light.y);
    if (wet <= 0.01) {
        return color;
    }
    color *= mix(1.0, 0.7, wet);
    if (n.y < 0.9) {
        return color;
    }
    vec3 wp = relPos + VeloCam.xyz;
    float puddle = smoothstep(0.5, 0.66, velo_noise2(wp.xz * 0.28) * 0.7 + velo_noise2(wp.xz * 0.9 + 5.0) * 0.3) * wet;
    float gloss = wet * 0.25 + puddle * 0.75;
    vec3 V = normalize(-relPos);
    vec2 rp = wp.xz * 1.8;
    float t = VeloCam.w * 1.2;
    float r0 = velo_ripples(rp, t);
    vec2 rg = vec2(velo_ripples(rp + vec2(0.03, 0.0), t) - r0, velo_ripples(rp + vec2(0.0, 0.03), t) - r0) / 0.03;
    vec3 N = normalize(vec3(-rg.x * 0.015 * puddle, 1.0, -rg.y * 0.015 * puddle));
    float fresnel = 0.04 + 0.96 * pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 5.0);
    vec3 refl = velo_sky_reflection(reflect(-V, N), fogColor) * mix(0.3, 1.0, light.y);
    color = mix(color, color * 0.6, puddle);
    return mix(color, refl, gloss * (0.15 + 0.85 * fresnel));
}

// Procedural lava: a slowly creeping dark crust with glowing cracks and hot spots.
vec3 velo_lava(vec3 relPos, vec3 n0) {
    vec3 wp = relPos + VeloCam.xyz;
    float t = VeloCam.w;
    vec2 p = (n0.y > 0.5 ? wp.xz : vec2(dot(wp.xz, normalize(vec2(-n0.z, n0.x) + 1e-4)), wp.y + t * 0.6)) * 0.9;
    vec2 warp = vec2(velo_noise2(p * 0.7 + t * 0.05), velo_noise2(p * 0.7 + 17.0 - t * 0.04)) * 1.6;
    float heat = velo_noise2(p + warp) * 0.6 + velo_noise2(p * 2.3 - warp + t * 0.1) * 0.4;
    float cracks = 1.0 - abs(velo_noise2(p * 1.6 + warp * 0.8) * 2.0 - 1.0);
    heat = clamp(heat * 0.7 + pow(cracks, 6.0) * 0.8, 0.0, 1.0);
    vec3 crust = vec3(0.18, 0.04, 0.01);
    vec3 glow = vec3(1.0, 0.38, 0.05);
    vec3 hot = vec3(1.0, 0.82, 0.38);
    vec3 c = mix(crust, glow, smoothstep(0.2, 0.6, heat));
    return mix(c, hot, smoothstep(0.7, 0.95, heat));
}

#endif
