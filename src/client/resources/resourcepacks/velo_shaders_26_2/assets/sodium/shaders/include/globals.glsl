// Sodium's globals plus Velo Shaders' data in the slot's spare tail (VeloShadersSodiumMixin, offset 192).
layout(std140) uniform u_Globals {
    mat4 u_ProjectionMatrix;
    mat4 u_ModelViewMatrix;

    vec4 u_FogColor;
    vec2 u_EnvironmentFog;
    vec2 u_RenderFog;

    vec2 u_TexelSize;
    vec2 u_TexCoordShrink;

    float u_FadePeriodInv;
    bool u_UseRGSS;

    vec4 VeloSun;   // xyz: direction to the sun (world), w: daylight 0-1
    vec4 VeloCam;   // xyz: camera position (x/z mod 2048), w: seconds
    vec4 VeloSky;   // rgb: vanilla sky colour, a: rain 0-1
    uvec4 VeloPack; // terrain settings, 8 bits each (see minecraft:velo_lib.glsl)
};
