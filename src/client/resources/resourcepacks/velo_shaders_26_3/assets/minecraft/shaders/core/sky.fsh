#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:velo_lib.glsl>
#include <minecraft:velo_sky.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec3 veloSkyPos;

layout(location = 0) out vec4 fragColor;

void main() {
    if (!velo_on() || !velo_flag(VELO_SKY)) {
        fragColor = apply_fog(ColorModulator, sphericalVertexDistance, cylindricalVertexDistance, 0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor);
        return;
    }
    vec3 color = velo_sky(normalize(veloSkyPos), ColorModulator.rgb, FogColor.rgb);
    // Vanilla fades the sky disc into the fog colour towards its rim (where it meets the clear
    // colour below the horizon) - kept, but later, so sunsets and the low sun survive.
    float fogValue = linear_fog_value(sphericalVertexDistance, 0.0, FogSkyEnd);
    color = mix(color, FogColor.rgb, pow(fogValue, 2.5) * FogColor.a);
    // Tiny dither: no banding in the gradients.
    color += (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
    fragColor = vec4(color, ColorModulator.a);
}
