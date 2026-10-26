#version 460 core

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_material.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:velo_lib.glsl>

layout(location = 0) in vec4 v_Color; // The interpolated vertex color
layout(location = 1) in vec2 v_TexCoord; // The interpolated block texture coordinates
layout(location = 2) in vec2 v_FragDistance; // The fragment's distance from the camera (cylindrical and spherical)
layout(location = 3) in float fadeFactor;
layout(location = 4) in vec3 v_VeloPos;
layout(location = 5) in vec2 v_VeloLight;
layout(location = 6) in float v_VeloWater;
layout(location = 7) in vec4 v_VeloTint;
layout(location = 8) in float v_VeloLava;
layout(location = 9) in float v_VeloEmissive;
layout(location = 10) in vec2 v_VeloFlow;

uniform sampler2D u_BlockTex; // The block texture

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor; // The output fragment for the color framebuffer
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
        color = sampleColorForAccumulation(color);
        vec4 fogColor = vec4(u_FogColor.rgb * color.a, u_FogColor.a);
    #else
        vec4 fogColor = u_FogColor;
    #endif

    #ifdef OIT_ALPHA_ONLY
    float factor = 1.0;
    #else
    float factor = fadeFactor;
    #endif

    return _linearFog(color, v_FragDistance, fogColor, u_EnvironmentFog, u_RenderFog, factor);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColor += textureLod(source, sampleUV, mipLevelExact);
    }
    rgssColor *= 0.25;

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
    vec4 texel = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);
    vec4 color = texel * v_Color; // Apply per-vertex color modulator

    bool veloPassTranslucent = false;
#ifdef ALPHA_CUTOUT
    veloPassTranslucent = ALPHA_CUTOUT < 0.3;
#endif
#if defined(OIT_ACCUMULATE) || defined(OIT_ALPHA_ONLY)
    veloPassTranslucent = true;
#endif
    // Glass (Sodium marks it): thinner streaks for more see-through, and in the translucent pass the clear part
    // stays (it carries the reflection) instead of being cut away.
    bool veloGlass = velo_on() && v_VeloEmissive > 2.5;
    float veloGlassFrame = 1.0;
    if (veloGlass) {
        vec2 local = velo_glass_local(v_VeloPos);
        veloGlassFrame = velo_glass_frame(local);
        float clarity = velo_glass_clarity() * (1.0 - veloGlassFrame);
        if (veloPassTranslucent) {
            color.a *= 1.0 - clarity * 0.8;
        } else if (velo_hash12(floor(local * 16.0) + 0.5) < clarity) {
            discard;
        }
    }

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT && !(veloGlass && veloPassTranslucent)) {
        discard;
    }
#endif

#ifndef OIT_ALPHA_ONLY
    if (((VeloPack.w >> 24u) & 16u) != 0u) {
        fragColor = color; // Velo Shaders' sun shadow pass: only the depth matters
        return;
    }
#endif

    float sunShare = -1.0;
    if (velo_on()) {
        vec3 faceNormal = velo_face_normal(v_VeloPos);
        // Velo's own light model on the albedo (texture x biome tint x vanilla face shade/AO)
        vec4 shaded = velo_shade(texel.rgb * v_VeloTint.rgb, v_VeloLight, dot(faceNormal, velo_light_dir()));
        float strength = velo_lighting();
        vec3 lit = mix(color.rgb, shaded.rgb, min(strength, 1.0)) * (1.0 + max(strength - 1.0, 0.0) * 0.25);
        if (v_VeloWater > 0.5) {
            vec3 lightColor = velo_shade(vec3(1.0), v_VeloLight, dot(faceNormal, velo_light_dir())).rgb;
            color = velo_water(color.a, lightColor, v_VeloPos, faceNormal, v_VeloLight, u_FogColor.rgb, v_VeloFlow);
        } else if (v_VeloLava > 0.5) {
            color.rgb = velo_lava(v_VeloPos, faceNormal);
            sunShare = 0.0; // it glows by itself: shadows don't darken it
        } else {
            // only real light sources glow (Sodium marks them) - not everything next to a torch
            if (v_VeloEmissive > 2.5) {
                color = velo_glass(vec4(lit, color.a), v_VeloPos, faceNormal, v_VeloLight, u_FogColor.rgb, veloPassTranslucent);
            } else if (v_VeloEmissive > 1.5) {
                color.rgb = velo_redstone(lit, texel.rgb * v_VeloTint.rgb);
                sunShare = shaded.a * 0.5;
            } else {
                color.rgb = v_VeloEmissive > 0.5 ? velo_emissive(lit, texel.rgb, vec2(1.0)) : lit;
            }
            color.rgb = velo_wet(color.rgb, v_VeloPos, faceNormal, v_VeloLight, u_FogColor.rgb);
            if (sunShare < 0.0) {
                sunShare = shaded.a;
            }
        }
        color.rgb = velo_fog(color.rgb, v_VeloPos, u_FogColor.rgb, v_VeloLight.y);
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
