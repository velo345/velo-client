#version 330

// Velo Shaders, deferred light pass. Rebuilds every pixel's view-space position and normal from the
// depth buffer, then:
//  - traces a ray towards the sun through the depth buffer: anything in the way casts a shadow,
//  - samples the hemisphere around the normal for ambient occlusion,
//  - lights the pixel with the nearby light-emitting blocks and hand-held lights in their own colour,
//  - colours sunlit surfaces warm and shaded ones with the sky's blue (indoors left alone),
//  - adds god rays and volumetric fog that scatters the sun's light.

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:velo_lib.glsl>
#moj_import <minecraft:velo_post.glsl>

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;
uniform sampler2D CloudsSampler;
uniform sampler2D RaysSampler;
uniform sampler2D LightBufSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 InDepthSize;
    vec2 CloudsSize;
    vec2 RaysSize;
    vec2 LightBufSize;
};

#moj_import <minecraft:velo_depth.glsl>

in vec2 texCoord;

out vec4 fragColor;


















// Bilateral upsample of the light buffer: of the four nearest low-res texels, weight those whose
// depth matches this pixel's - no halos where foreground meets background.
vec4 light_buffer(vec2 uv, float viewZ, float slopeTolerance) {
    vec2 size = LightBufSize;
    vec2 base = floor(uv * size - 0.5);
    vec2 f = uv * size - 0.5 - base;
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    vec4 best = vec4(1.0);
    float bestDiff = 1e9;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            vec2 cell = base + vec2(float(x), float(y));
            vec4 t = texture(LightBufSampler, (cell + 0.5) / size);
            float bilinear = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y);
            // tolerate the 8-bit depth step, or neighbouring rows flip weights and leave stripes
            // tolerance: the 8-bit step plus how fast depth changes across the screen here (sloped ground)
            float w = bilinear * exp(-max(abs(t.a - velo_encode_depth(viewZ)) - 1.5 / 255.0 - slopeTolerance, 0.0) * 160.0) + 1e-4;
            sum += t * w;
            weights += w;
            // thin things (kelp, grass, fences, leaves...) can have no matching texel at all: then the closest
            // depth wins instead of an even blend of the background behind them (that drew coloured outlines)
            float diff = abs(t.a - velo_encode_depth(viewZ));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = t;
            }
        }
    }
    return weights < 0.02 ? best : sum / weights;
}

void main() {
    int medium = int(VeloLightInfo3.z + 0.5);
    vec2 uv = texCoord;
    if (medium == 1 || medium == 2) {
        // underwater wobble / heat shimmer
        float t = VeloCam.w;
        float k = medium == 1 ? 0.0016 : 0.003;
        uv += vec2(sin(texCoord.y * 28.0 + t * 2.1), cos(texCoord.x * 23.0 + t * 1.7)) * k;
    }
    vec4 base = texture(InSampler, uv);
    // depth from the same (wobbled) spot as the colour: otherwise silhouettes got the background's distance -
    // under water that painted blue outlines around kelp and seagrass
    float depth = texture(InDepthSampler, uv).r;
    vec3 color = base.rgb;
    if (!velo_on()) {
        fragColor = vec4(color, 1.0);
        return;
    }
    vec3 lView = normalize((VeloView * vec4(velo_light_dir(), 0.0)).xyz);
    float day = VeloSun.w;
    float rain = VeloSky.a;
    int debugView = int(VeloLightInfo2.y);
    if (debugView == 8) {
        fragColor = vec4(base.rgb, 1.0);
        return;
    }
    if (debugView == 9) {
        fragColor = vec4(vec3(base.a), 1.0);
        return;
    }

    bool sky = is_sky(depth);
    vec3 p = view_pos(uv, sky ? (reversed_z() ? 0.0001 : 0.9999) : depth);
    float dist = length(p);
    // depth change per pixel (uniform control flow for the derivatives), scaled to the light buffer's texel size
    float encoded = velo_encode_depth(p.z);
    float depthSlope = (abs(dFdx(encoded)) + abs(dFdy(encoded))) * (InSize.y / LightBufSize.y) * 1.5;

    if (!sky && dist > 0.6) {
        vec3 n = view_normal_snapped(view_normal(uv, p));
        float near = 1.0 - smoothstep(48.0, 96.0, dist);
        if (debugView == 1) {
            fragColor = vec4(vec3(fract(dist / 4.0)), 1.0);
            return;
        }
        if (debugView == 2) {
            fragColor = vec4(n * 0.5 + 0.5, 1.0);
            return;
        }


        // AO, sun visibility and sky exposure come from the half-resolution light buffer,
        // upsampled depth-aware (the buffer keeps each texel's view depth in alpha).
        vec4 buf = light_buffer(uv, p.z, depthSlope);
        float ao = buf.r;
        if (debugView == 5) {
            fragColor = vec4(vec3(ao), 1.0);
            return;
        }
        color *= mix(1.0, ao, VeloLightInfo.z * 0.85 * near);

        // Sun (or moon) and shade: ray-traced visibility, warm light vs. the sky's blue.
        // Shadows take away exactly the sunlight the chunk shader put in (its share is in alpha);
        // ambient sky light and torch light stay. Entities and the sky carry no share: assume some.
        float lit = buf.g;
        if (debugView == 4) {
            fragColor = vec4(vec3(lit), 1.0);
            return;
        }
        float sunShare = base.a < 0.97 ? clamp((base.a - 0.02) / 0.9, 0.0, 1.0) : 0.45;
        // a little past the sun's share: the tonemapped curve squeezes it, shadows read too light otherwise
        color *= 1.0 - min(sunShare * 1.25, 0.7) * (1.0 - lit) * VeloLightInfo.y;

        if (debugView == 10) {
            fragColor = vec4(color, 1.0);
            return;
        }
        // Coloured point lights (blocks and hand-held)
        int count = int(VeloLightInfo.x);
        if (count > 0) {
            float maxc = max(color.r, max(color.g, color.b));
            vec3 albedo = color / max(maxc, 0.05) * clamp(maxc * 3.0 + 0.25, 0.25, 0.85);
            vec3 tint = vec3(0.0);
            vec3 pw = (VeloInvView * vec4(p, 1.0)).xyz;
            vec3 nw = (VeloInvView * vec4(n, 0.0)).xyz;
            for (int i = 0; i < 32; i++) {
                if (i >= count) {
                    break;
                }
                vec4 a = VeloLights[i * 2];
                vec4 c = VeloLights[i * 2 + 1];
                vec3 d = a.xyz - pw;
                float ld2 = dot(d, d);
                if (ld2 > a.w * a.w) {
                    continue;
                }
                float ld = sqrt(ld2);
                float falloff = 1.0 - ld / a.w;
                falloff *= falloff;
                float facing = max(dot(nw, d / max(ld, 0.001)), 0.0) * 0.75 + 0.25;
                tint += c.rgb * falloff * facing * c.a;
            }
            if (debugView == 3) {
                fragColor = vec4(tint, 1.0);
                return;
            }
            float strength = VeloLightInfo.w;
            float t = clamp(max(tint.r, max(tint.g, tint.b)), 0.0, 1.0);
            vec3 hue = tint / max(max(tint.r, max(tint.g, tint.b)), 0.001);
            // Tint vanilla's colourless block light towards the lights' colours, and add real light
            // where vanilla has none (hand-held lights, dark corners).
            color *= mix(vec3(1.0), hue, t * 0.25 * min(strength, 1.0));
            color += albedo * tint * 0.16 * strength;
        }
    }

    // Volumetric clouds (their own half-resolution pass), cut off by whatever stands in front.
    if (VeloLightInfo3.y > 0.0) {
        // The cloud texels are coarse: a surface closer than the cloud layer gets none (no cloud fringe
        // around leaves, kelp or players against a cloudy sky).
        // Above the clouds (mountains, tall builds, elytra) the same holds looking down: only surfaces past the cloud
        // plane get them, never the glass or leaves right in front of you.
        bool cloudsInFront = true;
        if (!sky) {
            vec3 dirW = (VeloInvView * vec4(p, 0.0)).xyz / max(dist, 0.001);
            float toPlane = 192.0 - VeloCam.y;
            cloudsInFront = abs(dirW.y) > 0.001 && toPlane / dirW.y > 0.0 && dist > toPlane / dirW.y;
        }
        if (cloudsInFront) {
            vec4 clouds = texture(CloudsSampler, uv);
            color = color * clouds.a + clouds.rgb;
        }
    }

    if (debugView == 11) {
        fragColor = vec4(color, 1.0);
        return;
    }
    // Atmospheric depth: thin exponential height fog, coloured like the horizon and glowing towards
    // the sun (forward scattering). Valleys and dawn get a little more.
    float fogAmount = VeloLightInfo2.w;
    if (medium == 1) {
        // Underwater: light fades with distance, red first; what scatters back has the water's
        // colour; caustics dance on lit surfaces.
        vec3 world = (VeloInvView * vec4(p, 1.0)).xyz;
        if (!sky) {
            vec2 cp = (world.xz + VeloCam.xz) * 0.7;
            float tt = VeloCam.w;
            float n = velo_noise2(cp + vec2(tt * 0.45, tt * 0.3)) + velo_noise2(cp * 1.7 - vec2(tt * 0.35, -tt * 0.25));
            float caustic = pow(1.0 - abs(n - 1.0), 7.0);
            color *= 1.0 + caustic * 0.7 * day * clamp(1.0 - dist / 24.0, 0.0, 1.0);
        }
        float d = sky ? 40.0 : min(dist, 40.0);
        vec3 absorb = exp(-d * vec3(0.1, 0.045, 0.032));
        vec3 wc = mix(velo_water_color(), vec3(velo_luma(velo_water_color())), 0.45);
        vec3 scatter = wc * mix(0.035, 0.26, day);
        color = color * absorb + scatter * (1.0 - absorb);
    } else if (medium == 2) {
        float d = sky ? 6.0 : min(dist, 6.0);
        float f = 1.0 - exp(-d * 0.9);
        color = mix(color, vec3(1.0, 0.36, 0.06), f) + vec3(0.25, 0.06, 0.0);
    } else if (medium == 3) {
        float d = sky ? 4.0 : min(dist, 4.0);
        color = mix(color, vec3(0.85, 0.9, 0.95), 1.0 - exp(-d * 1.2));
    } else if (fogAmount > 0.0) {
        vec3 world = (VeloInvView * vec4(p, 0.0)).xyz;
        float d = sky ? 260.0 : min(dist, 260.0);
        vec3 dir = world / max(length(world), 0.001);
        float midY = VeloCam.y + dir.y * d * 0.5;
        float dawn = 1.0 - smoothstep(0.05, 0.45, VeloSun.y);
        float density = fogAmount * (0.0011 + velo_fog_density() * 0.0028 + rain * 0.006)
                * exp(-max(midY - 63.0, 0.0) * 0.03) * (1.0 + dawn * 0.8);
        float transmittance = exp(-density * d);
        vec3 sunW = velo_light_dir();
        float phase = henyey_greenstein(dot(dir, sunW), 0.7) * 3.0;
        vec3 horizon = mix(VeloSky.rgb, vec3(velo_luma(VeloSky.rgb)), 0.35) * 1.05 + vec3(0.02);
        vec3 inscatter = horizon * mix(0.25, 1.0, day) + sun_light_color() * phase * mix(0.03, 0.2, day) * (1.0 - rain * 0.7);
        if (sky) {
            transmittance = mix(1.0, transmittance, 0.3);
        }
        color = color * transmittance + inscatter * (1.0 - transmittance);
    }

    if (debugView == 12) {
        fragColor = vec4(color, 1.0);
        return;
    }
    // God rays (their own quarter-resolution pass)
    if (VeloLightInfo2.z > 0.0) {
        color += texture(RaysSampler, texCoord).rgb;
    }

    fragColor = vec4(color, 1.0);
}
