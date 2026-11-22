#version 330

// Lyrics beat effects: on the beat (or the big moments) the picture kicks - colour split, flash, shake, glitch, zoom -
// each falling off within ~200 ms. Values come from the 4x1 ParamsSampler (LyricsPulse).

uniform sampler2D InSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;

out vec4 fragColor;

float rnd(float n) {
    return fract(sin(n * 91.3458) * 47453.5453);
}

void main() {
    vec4 p0 = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 p1 = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 p2 = texelFetch(ParamsSampler, ivec2(2, 0), 0);
    float zoom = p0.r * 0.08;          // fraction pulled towards the centre
    float split = p0.g * 0.02;         // chromatic split (fraction of the distance to the centre)
    float flash = p0.b * 0.3;          // brightness lift
    float glitch = p0.a;               // slice displacement
    vec2 centre = p1.rg;
    vec2 shake = (p1.ba - 0.5) * 0.04; // screen shake offset
    float seed = p2.r * 255.0;

    vec2 tc = texCoord + shake;
    if (glitch > 0.0) {
        float band = floor(tc.y * 22.0) + seed * 13.0;
        float r = rnd(band);
        if (r > 0.62) {
            tc.x += (rnd(band + 7.0) - 0.5) * glitch * 0.09;
        }
        split += glitch * 0.012 * step(0.8, r);
    }
    vec2 d = tc - centre;
    vec2 uv = centre + d * (1.0 - zoom);
    // the split grows towards the edges, like a real lens; plus a small constant so the middle shimmers too
    vec2 off = d * split + normalize(d + vec2(1e-5)) * split * 0.08;
    float r = texture(InSampler, clamp(uv + off, vec2(0.0005), vec2(0.9995))).r;
    float g = texture(InSampler, clamp(uv, vec2(0.0005), vec2(0.9995))).g;
    float b = texture(InSampler, clamp(uv - off, vec2(0.0005), vec2(0.9995))).b;
    vec3 c = vec3(r, g, b);
    c += c * flash;
    fragColor = vec4(c, 1.0);
}
