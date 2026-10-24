#version 330
#extension GL_ARB_separate_shader_objects : require

// Velo Shaders, light buffer at half resolution: ambient occlusion, sun visibility (shadow map,
// entity shadows, screen-space rays outside the map) and sky exposure, with the view depth in
// alpha (8-bit, non-linear: velo_encode_depth) for the light pass's depth-aware upsampling.

#include <minecraft:globals.glsl>
#include <minecraft:velo_lib.glsl>
#include <minecraft:velo_post.glsl>

uniform sampler2D InDepthSampler;
uniform sampler2D ShadowSampler;
uniform sampler2D NearShadowSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InDepthSize;
    vec2 ShadowSize;
    vec2 NearShadowSize;
};

#include <minecraft:velo_depth.glsl>

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float sun_visibility(vec3 p, vec3 n, vec3 l, float noise) {
    if (dot(n, l) <= 0.02) {
        return 1.0; // facing away: vanilla's face shading already darkens it
    }
    float dist = length(p);
    vec3 pos = p + n * (0.03 + dist * 0.002);
    float stepLen = 0.1 + dist * 0.004;
    pos += l * stepLen * noise;
    for (int i = 0; i < 24; i++) {
        pos += l * stepLen;
        stepLen *= 1.18;
        vec2 uv = project(pos);
        if (!on_screen(uv) || pos.z > -0.05) {
            break;
        }
        vec3 scene = view_at(uv);
        float diff = scene.z - pos.z; // > 0: the scene is in front of the ray
        if (diff > 0.015 && diff < 0.6 + stepLen * 2.0) {
            return 0.0;
        }
    }
    return 1.0;
}

float ambient_occlusion(vec3 p, vec3 n, float noise) {
    vec3 t = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(n, t);
    float radius = 0.7;
    float occlusion = 0.0;
    for (int i = 0; i < 6; i++) {
        float a = (float(i) + noise) * 2.39996;      // golden angle spiral
        float r = (float(i) + 0.5) / 6.0;
        vec3 dir = normalize(t * cos(a) * r + b * sin(a) * r + n * (1.0 - r * 0.7));
        vec3 s = p + dir * radius * mix(0.25, 1.0, r);
        vec2 uv = project(s);
        if (!on_screen(uv)) {
            continue;
        }
        float sceneZ = view_at(uv).z;
        float range = smoothstep(0.0, 1.0, radius / max(abs(p.z - sceneZ), 0.001));
        occlusion += (sceneZ > s.z + 0.06 ? 1.0 : 0.0) * range;
    }
    return 1.0 - occlusion / 6.0;
}

// One sun shadow map: 1 = lit, 0 = shadowed, -1 = outside it. radius: its half-size in blocks.
float shadow_lookup(sampler2D map, mat4 shadowMat, float radius, float size, vec3 world, vec3 nWorld, float slope, float noise) {
    float texelBlocks = 2.0 * radius / size;
    // normal offset grows at grazing sun angles (where acne starts)
    vec4 clip = shadowMat * vec4(world + nWorld * texelBlocks * (1.5 + slope * 2.5), 1.0);
    vec3 s = clip.xyz / clip.w;
    vec2 uv = s.xy * 0.5 + 0.5;
    float ref = VeloLightInfo2.x > 0.5 ? s.z : s.z * 0.5 + 0.5;
    if (uv.x <= 0.0 || uv.x >= 1.0 || uv.y <= 0.0 || uv.y >= 1.0 || ref <= 0.0 || ref >= 1.0) {
        return -1.0;
    }
    // PCSS: find the occluder, then soften by how far the shadow falls from it - sharp where
    // things touch the ground, wider under a high canopy. Fixed pattern per pixel (no frame noise).
    bool rev = reversed_z();
    float texel = 1.0 / size;
    float bias = 0.0003 + slope * 0.0009;
    float angle = noise * 6.2831853;
    float blockers = 0.0;
    float blockerDepth = 0.0;
    for (int i = 0; i < 8; i++) {
        float r = sqrt((float(i) + 0.5) / 8.0) * 7.0;
        float a = float(i) * 2.39996 + angle;
        float d = texture(map, uv + vec2(cos(a), sin(a)) * r * texel).r;
        bool blocks = rev ? d > ref + bias : d < ref - bias;
        if (blocks) {
            blockers += 1.0;
            blockerDepth += d;
        }
    }
    if (blockers < 0.5) {
        return 1.0;
    }
    blockerDepth /= blockers;
    // depth units -> blocks (the sun camera spans 256 blocks of depth); penumbra ~ sun's angular size
    float gap = abs(blockerDepth - ref) * 256.0;
    float penumbra = clamp(gap * 0.018 / texelBlocks, 0.75, 9.0);
    float lit = 0.0;
    for (int i = 0; i < 12; i++) {
        float r = sqrt((float(i) + 0.5) / 12.0) * penumbra;
        float a = float(i) * 2.39996 + angle;
        float d = texture(map, uv + vec2(cos(a), sin(a)) * r * texel).r;
        lit += rev ? (ref + bias >= d ? 1.0 : 0.0) : (ref - bias <= d ? 1.0 : 0.0);
    }
    lit /= 12.0;
    // fade out towards the map's edge
    vec2 edge = smoothstep(vec2(0.0), vec2(0.06), uv) * smoothstep(vec2(0.0), vec2(0.06), 1.0 - uv);
    return mix(1.0, lit, edge.x * edge.y);
}

// Sun shadows: the main map (terrain, far entities) and the near map (entities close by, hard edges).
float shadow_map(vec3 p, vec3 n, float noise) {
    if (VeloShadowInfo.x < 0.5) {
        return -1.0;
    }
    vec3 world = (VeloInvView * vec4(p, 1.0)).xyz;
    vec3 nWorld = (VeloInvView * vec4(n, 0.0)).xyz;
    float slope = clamp(1.0 - dot(nWorld, velo_light_dir()), 0.0, 1.0);
    float lit = shadow_lookup(ShadowSampler, VeloShadowMat, VeloShadowInfo.y, VeloShadowInfo.z, world, nWorld, slope, noise);
    if (VeloLightInfo3.w > 0.5) {
        float near = shadow_lookup(NearShadowSampler, VeloShadowNearMat, 12.0, NearShadowSize.x, world, nWorld, slope, noise);
        if (near >= 0.0) {
            lit = lit < 0.0 ? near : min(lit, near);
        }
    }
    return lit;
}

// How open to the sky a point is: a short ray straight up through the depth buffer.
float sky_exposure(vec3 p, vec3 n, vec3 up) {
    vec3 pos = p + n * 0.05;
    float stepLen = 0.35;
    for (int i = 0; i < 6; i++) {
        pos += up * stepLen;
        stepLen *= 1.5;
        vec2 uv = project(pos);
        if (!on_screen(uv) || pos.z > -0.05) {
            return 1.0;
        }
        vec3 scene = view_at(uv);
        float diff = scene.z - pos.z;
        if (diff > 0.05 && diff < 2.0 + stepLen) {
            return 0.0;
        }
    }
    return 1.0;
}

void main() {
    // Work on a real full-resolution pixel: a light-buffer texel centre rarely lands on one (the
    // window isn't exactly twice its size), and +-1 pixel normal taps then fetched the same pixel
    // twice - degenerate normals, AO broken in periodic rows.
    vec2 uv = (floor(texCoord * InDepthSize) + 0.5) / InDepthSize;
    vec2 px = 1.0 / InDepthSize;
    // nearest depth of the 2x2 pixels from here: a hairline crack between two block faces would
    // otherwise give a point one block deeper (dark AO along every seam)
    float d0 = texture(InDepthSampler, uv).r;
    float d1 = texture(InDepthSampler, uv + vec2(px.x, 0.0)).r;
    float d2 = texture(InDepthSampler, uv + vec2(0.0, px.y)).r;
    float d3 = texture(InDepthSampler, uv + px).r;
    float depth = reversed_z() ? max(max(d0, d1), max(d2, d3)) : min(min(d0, d1), min(d2, d3));
    if (!velo_on() || is_sky(depth)) {
        fragColor = vec4(1.0, 1.0, 1.0, 1.0);
        return;
    }
    vec2 pick = depth == d0 ? uv : depth == d1 ? uv + vec2(px.x, 0.0) : depth == d2 ? uv + vec2(0.0, px.y) : uv + px;
    vec3 p = view_pos(pick, depth);
    float dist = length(p);
    if (dist < 0.6) {
        fragColor = vec4(1.0, 1.0, 1.0, velo_encode_depth(p.z));
        return;
    }
    vec3 n = view_normal_snapped(view_normal(pick, p));
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float near = 1.0 - smoothstep(48.0, 96.0, dist);
    float ao = VeloLightInfo.z > 0.0 && near > 0.0 ? ambient_occlusion(p, n, noise) : 1.0;
    vec3 up = normalize((VeloView * vec4(0.0, 1.0, 0.0, 0.0)).xyz);
    float exposure = near > 0.0 ? sky_exposure(p, n, up) : 1.0;
    vec3 lView = normalize((VeloView * vec4(velo_light_dir(), 0.0)).xyz);
    float lit = 1.0;
    if (dot(n, lView) > 0.02) {
        float mapped = shadow_map(p, n, noise);
        // screen-space rays only where the shadow map doesn't reach (they depend on the view)
        lit = mapped < 0.0 ? (VeloLightInfo.y > 0.0 && near > 0.0 ? sun_visibility(p, n, lView, noise) : 1.0) : mapped;
        if (VeloShadowInfo.w > 0.5 && VeloLightInfo3.w < 0.5) {
            lit = min(lit, 1.0 - entity_shadow((VeloInvView * vec4(p, 1.0)).xyz, velo_light_dir(), true));
        }
    }
    fragColor = vec4(ao, lit, exposure, velo_encode_depth(p.z));
}
