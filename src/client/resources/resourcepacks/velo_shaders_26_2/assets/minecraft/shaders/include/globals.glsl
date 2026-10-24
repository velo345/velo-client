#version 330

// Vanilla's Globals block plus Velo Shaders' data (VeloShadersGlobalsMixin appends it every frame).
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
    vec4 VeloSun;    // xyz: direction to the sun (world), w: daylight 0-1
    vec4 VeloCam;    // xyz: camera position (x/z mod 2048), w: seconds
    vec4 VeloSky;    // rgb: vanilla sky colour, a: rain 0-1
    uvec4 VeloPack;  // terrain settings, 8 bits each (see velo_lib.glsl)
    uvec4 VeloPack2; // sky + post settings
    uvec4 VeloPack3; // colours: zenith, horizon, sunset, post tint (rgb + amount)
    uvec4 VeloPack4; // x: milky way, shooting stars, shader clouds
    mat4 VeloProj;      // world projection (this frame)
    mat4 VeloInvProj;
    mat4 VeloView;      // world -> view rotation
    mat4 VeloInvView;
    mat4 VeloShadowMat; // camera-relative world -> sun shadow map clip space
    vec4 VeloLightInfo;  // x: light count, y: ray-traced shadows, z: ambient occlusion, w: coloured light strength
    vec4 VeloLightInfo2; // x: 1 = depth range 0..1 (Vulkan), y: debug view, z: god rays, w: volumetric fog
    vec4 VeloLightInfo3; // x: sunlight & shade colours, y: shader cloud coverage, z: camera in 1 water / 2 lava / 3 powder snow, w: entity near map drawn
    vec4 VeloShadowInfo; // x: shadow map in use, y: its half-size (blocks), z: its size (texels), w: shadow capsule count
    vec4 VeloLights[64]; // per light: (camera-relative position, radius), (rgb, intensity)
    vec4 VeloEntities[32]; // per entity shadow capsule: (camera-relative feet, radius), (height, 0, 0, 0)
    mat4 VeloShadowNearMat; // camera-relative world -> entity near shadow map clip space
};
