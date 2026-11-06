#version 330

// Velo cosmetics kept on the GPU (GpuCosmetics): vanilla's entity vertex shader with the same switches (PER_FACE_LIGHTING
// for entity_cutout; EMISSIVE + NO_OVERLAY + NO_CARDINAL_LIGHTING for eyes), except that the mesh is stored once in its
// own space instead of being re-posed on the CPU every frame:
//   ModelViewMat = view * pose   (positions)
//   TextureMat   = pose          (camera-relative world space: normals for the light and fog distances, like vanilla's
//                                 CPU-posed vertices)
//   ModelOffset  = (block light, sky light, overlay u + v * 16) - the same for the whole draw, so the vertex data never
//                                 changes with the light or a hurt flash.
// The fragment shader is vanilla's core/entity, so it looks exactly like before.

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#moj_import <minecraft:light.glsl>
#endif
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

#ifndef NO_OVERLAY
uniform sampler2D Sampler1;
#endif

#ifndef EMISSIVE
uniform sampler2D Sampler2;
#endif

out float sphericalVertexDistance;
out float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif

#ifndef EMISSIVE
out vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
out vec4 overlayColor;
#endif

out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vec3 world = (TextureMat * vec4(Position, 1.0)).xyz;
    sphericalVertexDistance = fog_spherical_distance(world);
    cylindricalVertexDistance = fog_cylindrical_distance(world);

#ifdef PER_FACE_LIGHTING
    vec3 normal = normalize(mat3(TextureMat) * Normal);
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normalize(mat3(TextureMat) * Normal), Color);
#endif

#ifndef EMISSIVE
    lightMapColor = sample_lightmap(Sampler2, ivec2(int(ModelOffset.x), int(ModelOffset.y)));
#endif

#ifndef NO_OVERLAY
    int overlay = int(ModelOffset.z);
    overlayColor = texelFetch(Sampler1, ivec2(overlay & 15, overlay >> 4), 0);
#endif

    texCoord0 = UV0;
}
