#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif
#include <minecraft:velo_lib.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
layout(location = 5) in vec3 veloPos;
layout(location = 6) in vec2 veloLight;
layout(location = 7) in float veloWater;
layout(location = 8) in vec4 veloTint;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec4 texel = UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize);
    vec4 color = texel * vertexColor;
    #ifndef OIT_ALPHA_ONLY
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    #endif
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    // Runs in every OIT phase, so the alpha-only phase sees the same (water) alpha as the colour phase.
    float sunShare = -1.0;
    if (velo_on()) {
        vec3 faceNormal = velo_face_normal(veloPos);
        // Velo's own light model on the albedo (texture x biome tint x vanilla face shade/AO)
        vec4 shaded = velo_shade(texel.rgb * veloTint.rgb, veloLight, dot(faceNormal, velo_light_dir()));
        float strength = velo_lighting();
        vec3 lit = mix(color.rgb, shaded.rgb, min(strength, 1.0)) * (1.0 + max(strength - 1.0, 0.0) * 0.25);
        if (veloWater > 0.5 && color.a < 0.97) {
            vec3 lightColor = velo_shade(vec3(1.0), veloLight, dot(faceNormal, velo_light_dir())).rgb;
            color = velo_water(color.a, lightColor, veloPos, faceNormal, veloLight, FogColor.rgb, vec2(0.0));
        } else {
            color.rgb = velo_emissive(lit, texel.rgb, veloLight);
            color.rgb = velo_wet(color.rgb, veloPos, faceNormal, veloLight, FogColor.rgb);
            sunShare = shaded.a;
        }
        color.rgb = velo_fog(color.rgb, veloPos, FogColor.rgb, veloLight.y);
    }

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    bool velo_translucent = false;
    #ifdef ALPHA_CUTOUT
    velo_translucent = ALPHA_CUTOUT < 0.3;
    #endif
    #ifdef OIT_ACCUMULATE
    velo_translucent = true;
    #endif
    if (sunShare >= 0.0 && !velo_translucent) {
        fragColor.a = 0.02 + sunShare * 0.9; // for the light pass: how much of this is sunlight
    }
    #endif
}
