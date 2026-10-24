#version 330
#extension GL_ARB_separate_shader_objects : require

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_vertex.glsl>
#define VELO_VERTEX
#include <minecraft:velo_lib.glsl>

layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec2 v_TexCoord;

#ifdef USE_FOG
layout(location = 2) out vec2 v_FragDistance;
layout(location = 3) out float fadeFactor;
#endif

layout(location = 4) out vec3 v_VeloPos;
layout(location = 5) out vec2 v_VeloLight;
layout(location = 6) out float v_VeloWater;
layout(location = 7) out vec4 v_VeloTint;
layout(location = 8) out float v_VeloLava;
layout(location = 9) out float v_VeloEmissive;
layout(location = 10) out vec2 v_VeloFlow;

uniform isamplerBuffer u_SectionTimeInfo;

#ifdef VULKAN
layout(push_constant) uniform PC {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D u_LightTex; // The light map texture sampler
#endif

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

    // Sway code in the colour's alpha (255 = rigid): decode, then back to opaque.
    int veloCode = int(255.0 - floor(_vert_color.a * 255.0 + 0.5));
    v_VeloLava = veloCode == 6 ? 1.0 : 0.0;
    v_VeloEmissive = veloCode == 7 ? 1.0 : 0.0;
    // 16..31: flowing water, direction (veloCode - 16) / 16 of a turn
    bool veloFlowing = veloCode >= 16 && veloCode <= 31;
    float flowAngle = float(veloCode - 16) / 16.0 * 6.2831853;
    v_VeloFlow = veloFlowing ? vec2(cos(flowAngle), sin(flowAngle)) : vec2(0.0);
    if ((veloCode >= 5 && veloCode <= 7) || veloFlowing) {
        _vert_color.a = 1.0;
    }
    if (veloCode >= 2 && veloCode <= 4) {
        _vert_color.a = 1.0;
        if (velo_on()) {
            position += velo_sway(position, veloCode);
        }
    }

    v_VeloWater = 0.0;
    if (velo_on() && velo_flag(VELO_WATER) && (veloCode == 5 || veloFlowing || velo_water_tint(_vert_color.rgb))) {
        v_VeloWater = 1.0;
        if (!veloFlowing) {
            position.y += velo_water_offset(position, _vert_position.y); // still water only: flowing surfaces tore apart
        }
    }

#ifdef USE_FOG
    v_FragDistance = getFragDistance(position);

    int chunkId = int(_draw_id);
    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + uint(chunkId))).r;
    int fadeTime = u_CurrentTime - chunkFade;
    float elapsed = float(fadeTime);
    float fade = clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
    fadeFactor = (chunkFade < 0) ? 1.0 : fade;
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
#ifndef OIT_ALPHA_ONLY
    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
#else
    v_Color = _vert_color;
#endif
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision
    v_VeloPos = position;
    v_VeloLight = clamp(_vert_tex_light_coord * (256.0 / 240.0), 0.0, 1.0);
    v_VeloTint = _vert_color;
}
