#version 330

// Velo Shaders - helpers shared by the post passes (light, clouds). Needs minecraft:globals.glsl and velo_lib.glsl.

// Minecraft 26.2 on Vulkan renders with reversed Z (near = 1, far/sky = 0); its projection says which.
bool reversed_z() {
    return VeloProj[2][2] > 0.0;
}

bool is_sky(float depth) {
    return reversed_z() ? depth <= 0.000001 : depth >= 0.99999;
}

vec3 view_pos(vec2 uv, float depth) {
    float z = VeloLightInfo2.x > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 v = VeloInvProj * vec4(uv * 2.0 - 1.0, z, 1.0);
    return v.xyz / v.w;
}

float henyey_greenstein(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * 3.14159265 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

vec3 sun_light_color() {
    float day = VeloSun.w;
    return mix(vec3(0.55, 0.68, 1.0) * 0.55, velo_sun_color(), day);
}

// Sky-blue ambient light at the brightness of the sky.
vec3 ambient_color() {
    vec3 sky = max(VeloSky.rgb, vec3(0.02));
    return mix(vec3(1.0), sky / max(velo_luma(sky), 0.02), 0.4);
}

// Entity shadows: every nearby living entity is a soft capsule. Ray from w (camera-relative world)
// towards the light; 1 = fully shadowed. The penumbra widens with distance from the entity.
// skipSelf leaves out the camera's own entity (on the ground it's only a disc around your feet).
float entity_shadow(vec3 w, vec3 l, bool skipSelf) {
    int count = int(VeloShadowInfo.w);
    float shadow = 0.0;
    for (int i = 0; i < 16; i++) {
        if (i >= count) {
            break;
        }
        vec4 a = VeloEntities[i * 2];
        float h = VeloEntities[i * 2 + 1].x;
        if (skipSelf && length(a.xz) < 0.35 && a.y < 0.0 && a.y > -2.5) {
            continue;
        }
        float r = a.w;
        vec3 A = a.xyz + vec3(0.0, r, 0.0);
        vec3 v = vec3(0.0, max(h - 2.0 * r, 0.0), 0.0);
        // the point itself on (or inside) this entity: no self-shadow
        float onBody = length(w - (A + v * clamp(dot(w - A, v) / max(dot(v, v), 1e-4), 0.0, 1.0)));
        if (onBody < r * 1.15) {
            continue;
        }
        // closest approach between the ray w + l*s (s > 0) and the segment A + v*t
        vec3 w0 = w - A;
        float b = dot(l, v);
        float c = max(dot(v, v), 1e-4);
        float d = dot(l, w0);
        float e = dot(v, w0);
        float den = c - b * b;
        float t = den > 1e-5 ? clamp((e - b * d) / den, 0.0, 1.0) : 0.0;
        float sAlong = max(dot(A + v * t - w, l), 0.0);
        if (sAlong <= 0.0) {
            continue;
        }
        float dist = length(w + l * sAlong - (A + v * t));
        float soft = r * 0.3 + sAlong * 0.035;
        float occ = 1.0 - smoothstep(r - soft, r + soft, dist);
        shadow = max(shadow, occ * (1.0 - smoothstep(16.0, 48.0, sAlong)));
    }
    return shadow;
}

vec2 project(vec3 v) {
    vec4 c = VeloProj * vec4(v, 1.0);
    return c.xy / c.w * 0.5 + 0.5;
}

bool on_screen(vec2 uv) {
    return uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0;
}

// View depth squeezed into an 8-bit channel: fine steps up close, coarse far away.
float velo_encode_depth(float viewZ) {
    return 1.0 - exp(-max(-viewZ, 0.0) / 24.0);
}
