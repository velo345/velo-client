#version 330
#extension GL_ARB_separate_shader_objects : require

// Velo Shaders bloom, step 1: downsample the frame and keep what's bright (soft threshold).

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 o = 0.25 / OutSize;
    vec3 c = texture(InSampler, texCoord + vec2(o.x, o.y)).rgb + texture(InSampler, texCoord + vec2(-o.x, o.y)).rgb
            + texture(InSampler, texCoord + vec2(o.x, -o.y)).rgb + texture(InSampler, texCoord + vec2(-o.x, -o.y)).rgb;
    c *= 0.25;
    float peak = max(c.r, max(c.g, c.b));
    float weight = clamp((peak - 0.85) / 0.15, 0.0, 1.0);
    fragColor = vec4(c * weight * weight, 1.0);
}
