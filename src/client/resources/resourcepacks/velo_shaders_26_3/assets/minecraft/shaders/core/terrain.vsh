#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif
#define VELO_VERTEX
#include <minecraft:velo_lib.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;
layout(location = 5) out vec3 veloPos;
layout(location = 6) out vec2 veloLight;
layout(location = 7) out float veloWater;
layout(location = 8) out vec4 veloTint;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;

    veloWater = 0.0;
    // the translucent layer (water, glass, ice...): ALPHA_CUTOUT 0.1 (cutout uses 0.5), or the OIT phases
    bool veloTranslucent = false;
#ifdef ALPHA_CUTOUT
    veloTranslucent = ALPHA_CUTOUT < 0.3;
#endif
#if defined(OIT_ACCUMULATE) || defined(OIT_ALPHA_ONLY)
    veloTranslucent = true;
#endif
    if (veloTranslucent && velo_on() && velo_flag(VELO_WATER) && velo_water_tint(Color.rgb)) {
        veloWater = 1.0;
        pos.y += velo_water_offset(pos, Position.y);
    }

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    #ifndef OIT_ALPHA_ONLY
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    #else
    vertexColor = Color;
    #endif
    texCoord0 = UV0;

    const float chunkFullyVisibleRange = 16.0;
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
    veloPos = pos;
    veloLight = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
    veloTint = Color;
}
