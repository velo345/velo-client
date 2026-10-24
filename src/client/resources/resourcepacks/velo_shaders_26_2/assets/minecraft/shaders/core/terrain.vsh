#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#define VELO_VERTEX
#moj_import <minecraft:velo_lib.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 veloPos;
out vec2 veloLight;
out float veloWater;
out vec4 veloTint;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;

    veloWater = 0.0;
#ifdef ALPHA_CUTOUT
    // ALPHA_CUTOUT 0.1 = the translucent layer (water, glass, ice...); cutout uses 0.5.
    if (ALPHA_CUTOUT < 0.3 && velo_on() && velo_flag(VELO_WATER) && velo_water_tint(Color.rgb)) {
        veloWater = 1.0;
        pos.y += velo_water_offset(pos, Position.y);
    }
#endif

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    veloPos = pos;
    veloLight = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
    veloTint = Color;
}
