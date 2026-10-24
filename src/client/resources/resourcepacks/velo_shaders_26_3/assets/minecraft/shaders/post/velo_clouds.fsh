#version 330
#extension GL_ARB_separate_shader_objects : require

// Velo Shaders, volumetric clouds: a cumulus layer at 192-232 blocks, ray-marched at reduced
// resolution (its own target) and composited by the light pass. Puff shapes come from a
// precomputed 3D noise atlas instead of per-sample Worley noise.

#include <minecraft:globals.glsl>
#include <minecraft:velo_lib.glsl>
#include <minecraft:velo_post.glsl>

uniform sampler2D InDepthSampler;
uniform sampler2D NoiseSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InDepthSize;
    vec2 NoiseSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float cloud_hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float cloud_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(cloud_hash(i), cloud_hash(i + vec2(1.0, 0.0)), u.x),
            mix(cloud_hash(i + vec2(0.0, 1.0)), cloud_hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

const float CLOUD_BOTTOM = 192.0;
const float CLOUD_TOP = 232.0;

// The 3D noise atlas (64^3 in 8x8 tiles): r = big puffs, g = small puffs (both inverted Worley),
// b = wisps. Tiles every 1.0 in p.
vec2 cloud_atlas(vec2 xy, float slice) {
    vec2 tile = vec2(mod(slice, 8.0), floor(slice / 8.0));
    return (tile + (fract(xy) * 63.0 + 0.5) / 64.0) / 8.0;
}

vec4 cloud_noise3(vec3 p) {
    float z = fract(p.z) * 64.0;
    float z0 = floor(z);
    vec4 a = texture(NoiseSampler, cloud_atlas(p.xy, z0));
    vec4 b = texture(NoiseSampler, cloud_atlas(p.xy, mod(z0 + 1.0, 64.0)));
    return mix(a, b, z - z0);
}

// wp: world position (x/z continue VeloCam's mod-2048 frame, y absolute).
// Cumulus: 2D cells decide where clouds are, 3D billow noise carves the fluffy cauliflower edges.
float cloud_density(vec3 wp, bool detail) {
    float cover = VeloLightInfo3.y;
    vec3 wind = vec3(VeloCam.w * 2.4, 0.0, VeloCam.w * 0.8);
    vec3 p = wp + wind;
    vec2 q = p.xz * 0.0085;
    float cells = cloud_noise(q) * 0.62 + cloud_noise(q * 2.1 + 3.7) * 0.28 + cloud_noise(q * 4.3 + 7.1) * 0.1;
    float height = clamp((wp.y - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM), 0.0, 1.0);
    float d = cells - mix(0.72, 0.4, cover);
    // flat bottoms, rounded tops: thicker cells tower higher
    float top = mix(0.25, 1.0, clamp(d * 4.5, 0.0, 1.0));
    float profile = smoothstep(0.0, 0.07, height) * (1.0 - smoothstep(top * 0.45, top, height));
    d = d * 3.5 * profile;
    if (detail && d > 0.0) {
        vec4 n = cloud_noise3(p.xzy * vec3(1.0 / 140.0, 1.0 / 140.0, 1.0 / 90.0) + vec3(0.0, 0.0, -VeloCam.w * 0.0008));
        float puffs = n.r * 0.7 + n.g * 0.3;
        float wisps = n.b;
        // round puffs carve the rim; a little value noise keeps them from looking like balls
        float erode = (1.0 - puffs) * 0.95 + (1.0 - wisps) * 0.15;
        // carve the whole cloud (strongest at the rim and on top), so puffs show inside it too
        d -= erode * (0.6 + 0.4 * (1.0 - clamp(d, 0.0, 1.0))) * mix(0.75, 1.2, height);
    }
    return clamp(d * 1.6, 0.0, 1.0);
}

// rd: world view direction; returns (in-scattered colour, transmittance).
vec4 volumetric_clouds(vec3 rd, float sceneDist, float noise) {
    float camY = VeloCam.y;
    if (VeloLightInfo3.y <= 0.0 || abs(rd.y) < 0.0005) {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }
    float t0 = (CLOUD_BOTTOM - camY) / rd.y;
    float t1 = (CLOUD_TOP - camY) / rd.y;
    if (t0 > t1) {
        float tmp = t0;
        t0 = t1;
        t1 = tmp;
    }
    t0 = max(t0, 0.0);
    t1 = min(t1, min(sceneDist, 2400.0));
    if (t1 <= t0) {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }
    const int STEPS = 24;
    float stepLen = (t1 - t0) / float(STEPS);
    vec3 l = velo_light_dir();
    float day = VeloSun.w;
    float rain = VeloSky.a;
    vec3 sunColor = sun_light_color() * mix(0.18, 0.95, day) * (1.0 - rain * 0.6);
    vec3 ambient = mix(vec3(0.03, 0.04, 0.07), VeloSky.rgb * 0.6 + vec3(0.26), day) * mix(1.0, 0.6, rain);
    float phase = henyey_greenstein(dot(rd, l), 0.6) * 2.2 + 0.5;
    float transmittance = 1.0;
    vec3 color = vec3(0.0);
    float t = t0 + stepLen * noise;
    for (int i = 0; i < STEPS; i++) {
        vec3 pos = rd * t;
        vec3 wp = vec3(pos.x + VeloCam.x, camY + pos.y, pos.z + VeloCam.z);
        float density = cloud_density(wp, true);
        if (density > 0.005) {
            float toward = cloud_density(wp + l * 6.0, true) + cloud_density(wp + l * 18.0, false) * 0.8;
            // multiple scattering: light still soaks softly through dense cloud (bright undersides)
            float lightT = max(exp(-toward * 2.4), exp(-toward * 0.6) * 0.45) * (1.0 - entity_shadow(pos, l, false) * 0.9);
            // powder: dark edges where little light scatters back
            float powder = 1.0 - exp(-density * 4.0);
            float h01 = clamp((wp.y - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM), 0.0, 1.0);
            vec3 scatter = sunColor * lightT * phase * mix(0.55, 1.0, powder) + ambient * mix(0.75, 1.1, h01);
            float alpha = 1.0 - exp(-density * min(stepLen, 10.0) * 0.22);
            color += transmittance * alpha * scatter;
            transmittance *= 1.0 - alpha;
            if (transmittance < 0.02) {
                break;
            }
        }
        t += stepLen;
    }
    // far clouds melt into the haze
    float fade = exp(-t0 * 0.00055);
    return vec4(color * fade, mix(1.0, transmittance, fade));
}

void main() {
    if (!velo_on() || VeloLightInfo3.y <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    // nearest scene depth in this low-res texel's footprint (clouds stop at the closest surface)
    vec2 px = 0.5 / OutSize;
    float d0 = texture(InDepthSampler, texCoord + vec2(-px.x, -px.y)).r;
    float d1 = texture(InDepthSampler, texCoord + vec2(px.x, -px.y)).r;
    float d2 = texture(InDepthSampler, texCoord + vec2(-px.x, px.y)).r;
    float d3 = texture(InDepthSampler, texCoord + vec2(px.x, px.y)).r;
    float depth = reversed_z() ? max(max(d0, d1), max(d2, d3)) : min(min(d0, d1), min(d2, d3));
    bool sky = is_sky(depth);
    vec3 p = view_pos(texCoord, sky ? (reversed_z() ? 0.0001 : 0.9999) : depth);
    vec3 rd = normalize((VeloInvView * vec4(p, 0.0)).xyz);
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    fragColor = volumetric_clouds(rd, sky ? 1e9 : length(p), noise);
}
