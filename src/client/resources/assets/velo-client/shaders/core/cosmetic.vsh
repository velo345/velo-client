#version 330

// Velo cosmetics kept on the GPU (GpuCosmetics): vanilla's entity vertex shader (PER_FACE_LIGHTING, as entity_cutout),
// except that the mesh is stored once in its own space instead of being re-posed on the CPU every frame:
//   ModelViewMat = view * pose   (positions)
//   TextureMat   = pose          (camera-relative world space: normals for the light and fog distances, like vanilla's
//                                 CPU-posed vertices)
//   ModelOffset  = (block light, sky light, overlay u + v * 16) - the same for the whole draw, so the vertex data never
//                                 changes with the light or a hurt flash.
// The fragment shader is vanilla's core/entity, so it looks exactly like before.

#moj_import <minecraft:light.glsl>
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

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vec3 world = (TextureMat * vec4(Position, 1.0)).xyz;
    sphericalVertexDistance = fog_spherical_distance(world);
    cylindricalVertexDistance = fog_cylindrical_distance(world);

    vec3 normal = normalize(mat3(TextureMat) * Normal);
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);

    lightMapColor = sample_lightmap(Sampler2, ivec2(int(ModelOffset.x), int(ModelOffset.y)));
    int overlay = int(ModelOffset.z);
    overlayColor = texelFetch(Sampler1, ivec2(overlay & 15, overlay >> 4), 0);

    texCoord0 = UV0;
}
