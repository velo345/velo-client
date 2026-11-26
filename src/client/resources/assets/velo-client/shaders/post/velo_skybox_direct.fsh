#version 330

// Velo Custom Skybox, direct variant: drawn right after vanilla's sky and before the world (START_MAIN), straight into
// the main target - terrain, clouds, text and everything else are drawn over it afterwards. Otherwise as velo_skybox:
// Velo Custom Skybox: wherever the depth buffer still holds its clear value (nothing was drawn there - the sky), the
// pixel becomes the skybox panorama (equirectangular, 2:1) seen in that direction. Terrain, clouds, water, entities and
// the hand stay in front. Per-frame values (camera axes, fov, rotation, opacity...) come from the 12x1 ParamsSampler.

uniform sampler2D SkySampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;

out vec4 fragColor;

float s16(vec2 hl) {
    return (floor(hl.x * 255.0 + 0.5) * 256.0 + floor(hl.y * 255.0 + 0.5)) / 65535.0 * 2.0 - 1.0;
}

float val(int i) {
    vec4 p = texelFetch(ParamsSampler, ivec2(i / 2, 0), 0);
    return (i % 2 == 0) ? s16(p.rg) : s16(p.ba);
}

const float PI = 3.14159265;

vec3 hash3(vec3 p) {
    p = fract(p * vec3(443.897, 441.423, 437.195));
    p += dot(p, p.yzx + 19.19);
    return fract((p.xxy + p.yzz) * p.zyx);
}

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash2(i), hash2(i + vec2(1.0, 0.0)), f.x), mix(hash2(i + vec2(0.0, 1.0)), hash2(i + vec2(1.0, 1.0)), f.x), f.y);
}

float fbm2(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * vnoise(p);
        p = p * 2.03 + 7.1;
        a *= 0.5;
    }
    return v;
}

// The moon as a lit sphere: soft maria and craters, the phase's terminator, a darker limb (Velo Shaders' moon).
vec3 moon(vec3 dir, vec3 moonDir, float radius, float phase01, out float coverage) {
    coverage = 0.0;
    vec3 right = normalize(cross(moonDir, vec3(0.0, 0.0, 1.0)));
    vec3 upv = cross(right, moonDir);
    vec2 uv = vec2(dot(dir, right), dot(dir, upv)) / sin(radius);
    float r = length(uv);
    if (r >= 1.0 || dot(dir, moonDir) < 0.0) {
        return vec3(0.0);
    }
    vec3 sphere = vec3(uv, sqrt(1.0 - r * r));
    float phase = phase01 * 2.0 * PI;
    vec3 sunSide = vec3(sin(phase), 0.0, cos(phase));
    float lit = smoothstep(-0.06, 0.12, dot(sphere, sunSide));
    float maria = fbm2(uv * 2.2 + 3.0);
    float craters = fbm2(uv * 7.0 + 11.0);
    float surface = 0.62 + 0.25 * smoothstep(0.35, 0.65, maria) + 0.13 * craters;
    float limb = mix(0.75, 1.0, sphere.z);
    coverage = smoothstep(1.0, 0.94, r);
    return vec3(0.93, 0.95, 1.0) * surface * limb * (lit * 1.2 + 0.035);
}

// Stars drawn per pixel (a panorama can't hold sharp ones): one candidate star per cell of a 3D grid around the camera,
// each a disc about one or two screen pixels wide however the screen is sized.
float stars(vec3 dir, float cells, float density, float pxAngle, float time) {
    vec3 g = dir * cells;
    vec3 base = floor(g);
    // a star sits well inside its cell and is tiny, so only the 8 cells around the nearest corner can reach this pixel
    vec3 side = sign(fract(g) - 0.5);
    float light = 0.0;
    for (int i = 0; i < 8; i++) {
        vec3 c = base + vec3(float(i & 1), float((i >> 1) & 1), float((i >> 2) & 1)) * side;
        vec3 h = hash3(c);
        if (h.x > density) {
            continue;
        }
        vec3 sp = normalize(c + 0.15 + 0.7 * hash3(c + 17.0));
        float ang = length(dir - sp); // chord = angle for tiny angles, no acos per cell
        float size = pxAngle * (0.45 + 0.55 * h.y * h.y);
        float b = 0.22 + 0.78 * h.z * h.z * h.z;
        b *= 0.8 + 0.2 * sin(time * (1.5 + 3.0 * h.x) + h.y * 40.0);
        light = max(light, b * smoothstep(size, size * 0.35, ang));
    }
    return light;
}

void main() {
    vec3 right = vec3(val(0), val(1), val(2));
    vec3 up = vec3(val(3), val(4), val(5));
    vec3 fwd = vec3(val(6), val(7), val(8));
    float tanHalf = (val(9) + 1.0) * 2.0;
    float aspect = (val(10) + 1.0) * 2.0;
    float turn = (val(11) + 1.0) * 0.5;
    float opacity = (val(12) + 1.0) * 0.5;
    float keep = (val(13) + 1.0) * 0.5;
    float bright = (val(14) + 1.0) * 0.5;
    float flipY = val(15) > 0.0 ? -1.0 : 1.0;

    vec2 ndc = texCoord * 2.0 - 1.0;
    ndc.y *= flipY;
    vec3 dir = normalize(fwd + ndc.x * tanHalf * aspect * right + ndc.y * tanHalf * up);
    float lon = atan(dir.x, -dir.z) / (2.0 * PI) + 0.5 + turn;
    float lat = asin(clamp(dir.y, -1.0, 1.0));
    vec2 uv = vec2(fract(lon), 0.5 - lat / PI);
    // derivatives of the continuous longitude: fract() jumps 1 -> 0 at one meridian, and the hardware read that jump as a
    // huge texel step there - a thin vertical line through the sky (owner, 2026-11-25)
    vec2 smoothUv = vec2(lon, uv.y);
    vec3 sky = textureGrad(SkySampler, uv, dFdx(smoothUv), dFdy(smoothUv)).rgb * bright;
    float starAmount = (val(17) + 1.0) * 0.5;
    if (starAmount > 0.0) {
        float time = (val(16) + 1.0) * 30.0;
        float starBright = (val(18) + 1.0) * 0.5;
        // the panorama's turn applies to the stars too
        float a = turn * 2.0 * PI;
        vec3 sd = vec3(dir.x * cos(a) - dir.z * sin(a), dir.y, dir.x * sin(a) + dir.z * cos(a));
        float pxAngle = 2.0 * tanHalf / max(1.0, (val(22) + 1.0) * 2048.0);
        float lum = dot(sky, vec3(0.299, 0.587, 0.114));
        float vis = (1.0 - smoothstep(0.12, 0.42, lum)) * smoothstep(-0.08, 0.06, sd.y) * starBright;
        if (vis > 0.0) {
            // probability per cell: a few percent (bright, sparse grid) and a little more on the fine grid (faint)
            float s = stars(sd, 90.0, 0.011 * starAmount, pxAngle, time) + stars(sd, 220.0, 0.003 * starAmount, pxAngle * 0.8, time) * 0.55;
            vec3 tint = mix(vec3(1.0, 0.9, 0.78), vec3(0.8, 0.88, 1.0), hash3(floor(sd * 220.0)).x);
            sky += tint * s * vis;
        }
    }
    // Sun and moon drawn here, where the game has them (vanilla's are hidden under the panorama; letting their bright
    // pixels through left a ragged, broken moon).
    if (keep > 0.5) {
        vec3 sunDir = normalize(vec3(val(19), val(20), 0.0));
        float above = smoothstep(-0.04, 0.03, dir.y);
        float mu = dot(dir, sunDir);
        float low = 1.0 - smoothstep(0.0, 0.38, abs(sunDir.y + 0.04));
        vec3 sunColor = mix(vec3(1.0, 0.96, 0.88), vec3(1.0, 0.62, 0.36), low * 0.7);
        float disc = smoothstep(cos(0.045), cos(0.036), mu);
        float glow = pow(max(mu, 0.0), 260.0) * 0.32 + pow(max(mu, 0.0), 12.0) * 0.06 * (0.4 + low);
        sky += sunColor * (disc * 1.5 + glow) * above;
        vec3 moonDir = -sunDir;
        float cover;
        vec3 m = moon(dir, moonDir, 0.034, (val(21) + 1.0) * 0.5, cover);
        sky = mix(sky, m, cover * above);
        float halo = max(dot(dir, moonDir), 0.0);
        sky += vec3(0.55, 0.65, 1.0) * (pow(halo, 180.0) * 0.18 + pow(halo, 24.0) * 0.05) * above * smoothstep(-0.1, 0.2, moonDir.y);
    }
    fragColor = vec4(sky, 1.0);
}
