#version 330

// Velo Turbo GPU model meshes (GpuModelMeshes): vanilla's entity vertex shader (same switches) for models whose cubes
// are stored once on the GPU, each vertex tagged with its model part (UV1.x). Each frame only the parts' matrices are
// written, exactly as vanilla's ModelPart walk computed them on the CPU:
//   PartPose[i]   = the part's pose (camera-relative world space - what vanilla wrote into every vertex)
//   PartNormal[i] = the part's normal matrix (mat3 in a mat4)
//   ModelViewMat  = the camera rotation, ColorModulator = the submit's tint, ModelOffset = (block light, sky light,
//                   overlay u + v * 16) for the whole model.
// The fragment shader is vanilla's core/entity, so it looks the same.

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#moj_import <minecraft:light.glsl>
#endif
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

layout(std140) uniform VeloParts {
    mat4 PartPose[64];
    mat4 PartNormal[64];
};

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

// Velo Shaders' entity fragment shader also reads these (see cosmetic.vsh); vanilla's ignores them.
out vec4 veloColor;
out vec3 veloNormal;
out vec2 veloLight;
out vec3 veloPos;
flat out float veloWorld;

void main() {
    int part = clamp(UV1.x, 0, 63);
    vec3 world = (PartPose[part] * vec4(Position, 1.0)).xyz;
    gl_Position = ProjMat * ModelViewMat * vec4(world, 1.0);

    sphericalVertexDistance = fog_spherical_distance(world);
    cylindricalVertexDistance = fog_cylindrical_distance(world);

    vec3 normal = normalize(mat3(PartNormal[part]) * Normal);
#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, Color);
#endif

#ifndef EMISSIVE
    lightMapColor = sample_lightmap(Sampler2, ivec2(int(ModelOffset.x), int(ModelOffset.y)));
#endif

#ifndef NO_OVERLAY
    int overlay = int(ModelOffset.z);
    overlayColor = texelFetch(Sampler1, ivec2(overlay & 15, overlay >> 4), 0);
#endif

    texCoord0 = UV0;

    veloColor = Color;
    veloNormal = normal;
    veloLight = clamp(vec2(ModelOffset.x, ModelOffset.y) / 240.0, 0.0, 1.0);
    veloPos = world;
    veloWorld = abs(ProjMat[2][3]) > 0.5 ? 1.0 : 0.0;
}
