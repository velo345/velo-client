#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:velo_lib.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in float veloBlockLight;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    // Velo Shaders: glowing particles (flames, lava drips, soul fire, end rods... sit at full block
    // light) shine brighter, so the bloom picks them up.
    if (velo_on() && veloBlockLight > 0.93) {
        float peak = max(color.r, max(color.g, color.b));
        color.rgb += color.rgb * smoothstep(0.45, 0.9, peak) * velo_emissive_glow() * 0.8;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
