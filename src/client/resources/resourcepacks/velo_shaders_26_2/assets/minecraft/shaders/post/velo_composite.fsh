#version 330

// Velo Shaders, final grade: bloom, exposure with a soft highlight roll-off, contrast,
// saturation, colour tint and vignette.

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:velo_lib.glsl>

uniform sampler2D InSampler;
uniform sampler2D BloomSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 BloomSize;
};

in vec2 texCoord;

out vec4 fragColor;

vec3 rolloff(vec3 c) {
    // Linear below 0.75, then eases into white instead of clipping.
    vec3 over = max(c - 0.75, 0.0);
    return min(c, vec3(0.75)) + 0.25 * (1.0 - exp(-over / 0.25));
}

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    float bloom = velo_byte(VeloPack2.x, 0) * 1.5;
    float exposure = velo_byte(VeloPack2.x, 1) * 2.0;
    float contrast = velo_byte(VeloPack2.x, 2) * 2.0;
    float saturation = velo_byte(VeloPack2.x, 3) * 2.0;

    c += texture(BloomSampler, texCoord).rgb * bloom * 0.85;
    c = rolloff(c * exposure);
    // Contrast as a power curve around mid grey: blacks stay black instead of being crushed (a linear
    // curve around 0.5 took dark nights and caves down to nothing).
    c = clamp(pow(max(c, 0.0) / 0.45, vec3(contrast)) * 0.45, 0.0, 1.0);
    float l = velo_luma(c);
    c = max(mix(vec3(l), c, saturation), 0.0);

    // Filmic split tone: cool shadows, warm highlights (scaled with the grading strength).
    float grade = clamp(abs(contrast - 1.0) * 2.0 + abs(saturation - 1.0) * 2.0, 0.0, 1.0);
    float lum = velo_luma(c);
    c = mix(c, c * vec3(0.93, 1.0, 1.09), (1.0 - lum) * (1.0 - lum) * 0.18 * grade);
    c = mix(c, c * vec3(1.07, 1.0, 0.9), lum * lum * 0.12 * grade);

    vec3 tint = velo_rgb(VeloPack3.w);
    // The tint is a daylight grade: at night it mostly steps aside (a warm tint made nights orange).
    float tintAmount = velo_byte(VeloPack3.w, 3) * mix(0.3, 1.0, VeloSun.w);
    c *= mix(vec3(1.0), tint / max(velo_luma(tint), 0.05), tintAmount);

    float vignette = velo_byte(VeloPack2.y, 0);
    vec2 d = (texCoord - 0.5) * vec2(1.0, OutSize.y / OutSize.x) * 1.6;
    c *= 1.0 - vignette * 0.75 * smoothstep(0.35, 1.0, length(d));

    c += (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
