#version 330
#extension GL_ARB_separate_shader_objects : require

// 26.3 (renderpearl) version of cosmetic.vsh - same idea (see there), vanilla 26.3 entity.vsh syntax: #include and
// explicit locations that match vanilla's core/entity fragment shader.
//   ModelViewMat = view * pose, TextureMat = pose, ModelOffset = (block light, sky light, overlay u + v * 16)

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#include <minecraft:light.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler1;
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
#else
layout(location = 2) out vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) out vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) out vec4 overlayColor;
#endif

layout(location = 6) out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vec3 world = (TextureMat * vec4(Position, 1.0)).xyz;
    sphericalVertexDistance = fog_spherical_distance(world);
    cylindricalVertexDistance = fog_cylindrical_distance(world);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normalize(mat3(TextureMat) * Normal));
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normalize(mat3(TextureMat) * Normal), Color);
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    lightMapColor = sample_lightmap(Sampler2, ivec2(int(ModelOffset.x), int(ModelOffset.y)));
#endif

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    int overlay = int(ModelOffset.z);
    overlayColor = texelFetch(Sampler1, ivec2(overlay & 15, overlay >> 4), 0);
#endif

    texCoord0 = UV0;
}
