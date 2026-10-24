#version 330
#extension GL_ARB_separate_shader_objects : require

// Velo Shaders, god rays at quarter resolution: march from each pixel towards the sun on screen and
// count how much open sky lies on the way.

#include <minecraft:globals.glsl>
#include <minecraft:velo_lib.glsl>
#include <minecraft:velo_post.glsl>

uniform sampler2D InDepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InDepthSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float hash(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

vec2 InSize = InDepthSize;

vec3 god_rays(vec2 uv, vec3 lView) {
    vec4 clip = VeloProj * vec4(lView * 1000.0, 1.0);
    if (clip.w <= 0.0) {
        return vec3(0.0);
    }
    vec2 sunUv = clip.xy / clip.w * 0.5 + 0.5;
    vec2 delta = (sunUv - uv) / 28.0;
    vec2 pos = uv + delta * hash(gl_FragCoord.xy);
    float sum = 0.0;
    float weight = 1.0;
    for (int i = 0; i < 28; i++) {
        if (on_screen(pos) && is_sky(texture(InDepthSampler, pos).r)) {
            sum += weight;
        }
        weight *= 0.965;
        pos += delta;
    }
    float aspect = InSize.x / InSize.y;
    float toSun = length((sunUv - uv) * vec2(aspect, 1.0));
    float falloff = exp(-toSun * 1.2);
    return sun_light_color() * (sum / 28.0) * falloff;
}

void main() {
    float amount = VeloLightInfo2.z;
    vec3 lView = normalize((VeloView * vec4(velo_light_dir(), 0.0)).xyz);
    if (!velo_on() || amount <= 0.0 || lView.z >= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float day = VeloSun.w;
    float rain = VeloSky.a;
    // Light shafts only show looking towards the sun; elsewhere the march through distant sky just
    // leaves faint bands.
    vec3 viewDir = normalize(view_pos(texCoord, reversed_z() ? 1.0 : 0.0));
    float towards = pow(max(dot(viewDir, lView), 0.0), 3.0);
    fragColor = vec4(god_rays(texCoord, lView) * towards * amount * mix(0.35, 1.2, day) * (1.0 - rain * 0.8), 1.0);
}
