#version 330
#extension GL_ARB_separate_shader_objects : require

// Velo Shaders bloom, step 2: separable 9-tap Gaussian (5 bilinear fetches).

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform VeloBlur {
    vec2 BlurDir;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 step = BlurDir / InSize;
    vec3 c = texture(InSampler, texCoord).rgb * 0.2270270270;
    c += texture(InSampler, texCoord + step * 1.3846153846).rgb * 0.3162162162;
    c += texture(InSampler, texCoord - step * 1.3846153846).rgb * 0.3162162162;
    c += texture(InSampler, texCoord + step * 3.2307692308).rgb * 0.0702702703;
    c += texture(InSampler, texCoord - step * 3.2307692308).rgb * 0.0702702703;
    fragColor = vec4(c, 1.0);
}
