#version 330

// Velo Color Grading: saturation, vibrance, contrast, brightness, warmth, vignette and sharpening in one pass.
// The settings come from an 8x1 texture (ParamsSampler) the client rewrites when a slider moves.

uniform sampler2D InSampler;
uniform sampler2D ParamsSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 ParamsSize;
};

in vec2 texCoord;

out vec4 fragColor;

vec4 param(int i) {
    return texelFetch(ParamsSampler, ivec2(i, 0), 0);
}

void main() {
    vec4 p0 = param(0);
    vec4 p1 = param(1);
    float saturation = p0.r * 2.0;
    float contrast = p0.g * 2.0;
    float brightness = p0.b - 0.5;
    float vibrance = p0.a * 2.0 - 1.0;
    float warmth = p1.r * 2.0 - 1.0;
    float vignette = p1.g;
    float sharpen = p1.b * 1.5;
    float gamma = 0.5 + p1.a;

    vec3 c = texture(InSampler, texCoord).rgb;
    if (sharpen > 0.001) {
        vec2 px = 1.0 / InSize;
        vec3 blur = (texture(InSampler, texCoord + vec2(px.x, 0.0)).rgb + texture(InSampler, texCoord - vec2(px.x, 0.0)).rgb
                + texture(InSampler, texCoord + vec2(0.0, px.y)).rgb + texture(InSampler, texCoord - vec2(0.0, px.y)).rgb) * 0.25;
        c = clamp(c + (c - blur) * sharpen, 0.0, 1.0);
    }
    c = pow(max(c, 0.0), vec3(1.0 / gamma));
    c += brightness * 0.6;
    c = (c - 0.5) * contrast + 0.5;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, saturation);
    if (abs(vibrance) > 0.001) {
        // vibrance: boosts dull colours more than already-saturated ones
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float sat = mx - mn;
        c = mix(vec3(dot(c, vec3(0.2126, 0.7152, 0.0722))), c, 1.0 + vibrance * (1.0 - sat));
    }
    c *= vec3(1.0 + warmth * 0.12, 1.0 + warmth * 0.02, 1.0 - warmth * 0.12);
    if (vignette > 0.001) {
        vec2 d = texCoord - 0.5;
        float v = 1.0 - smoothstep(0.35, 0.9, length(d * vec2(1.0, OutSize.y / OutSize.x) * 1.35));
        c *= mix(1.0, v, vignette);
    }
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
