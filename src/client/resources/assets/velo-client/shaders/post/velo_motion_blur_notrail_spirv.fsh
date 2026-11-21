#version 330
#extension GL_ARB_separate_shader_objects : require

// Velo Motion Blur: smears the world picture along the camera's own motion (turning = directional streaks, fast
// travel = zoom streaks from the centre) (no trail on this version).
// Every value is per frame and comes from a 4x1 texture (ParamsSampler) rewritten by MotionBlurModule.

uniform sampler2D InSampler;

uniform sampler2D ParamsSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 ParamsSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// 16-bit signed value in [-1, 1] from two bytes
float s16(float hi, float lo) {
    return (floor(hi * 255.0 + 0.5) * 256.0 + floor(lo * 255.0 + 0.5)) / 65535.0 * 2.0 - 1.0;
}

void main() {
    vec4 p0 = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 p1 = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec2 dir = vec2(s16(p0.r, p0.g), s16(p0.b, p0.a)) * 0.25;   // UV length of the streak
    float zoom = p1.r * 0.25;                                    // radial streak length (fraction of distance to centre)
    float trail = p1.g * 0.95;                                   // share of the previous frame
    int samples = max(2, int(p1.b * 64.0 + 0.5));

    vec2 radial = (texCoord - 0.5) * zoom;
    vec2 streak = dir + radial;
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int i = 0; i < 64; i++) {
        if (i >= samples) {
            break;
        }
        // centred streak, weights fall off towards the ends (looks like a real shutter, no hard double edges)
        float t = float(i) / float(samples - 1) - 0.5;
        float w = 1.0 - abs(t) * 1.2;
        sum += texture(InSampler, clamp(texCoord - streak * t, vec2(0.0005), vec2(0.9995))).rgb * w;
        weight += w;
    }
    vec3 c = sum / weight;

    fragColor = vec4(c, 1.0);
}
