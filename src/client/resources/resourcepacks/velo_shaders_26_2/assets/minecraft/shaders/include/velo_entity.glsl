#version 330

// Velo Shaders' light on entities and block entities (mobs, players, chests, signs, beds...): the terrain's light
// model from the entity's own light level and real normal, instead of vanilla's two fixed lamps. Fragment only;
// needs velo_lib.glsl.

// albedo: texture x tint (with the hurt overlay), tex: the raw texture, vanilla: what vanilla would draw.
// light: (block, sky) 0-1, relPos: camera-relative position.
vec3 velo_entity_shade(vec3 albedo, vec3 tex, vec3 vanilla, vec3 n, vec2 light, vec3 relPos) {
    vec3 N = normalize(n);
    vec4 shaded = velo_shade(albedo, light, dot(N, velo_light_dir()));
    float strength = velo_lighting();
    vec3 rgb = mix(vanilla, shaded.rgb, min(strength, 1.0)) * (1.0 + max(strength - 1.0, 0.0) * 0.25);
    // Mobs that light themselves are drawn at full block light (blazes, magma cubes, vexes, allays, anything on
    // fire): their bright parts glow and a soft rim of their own colour wraps them - the bloom picks both up.
    float self = smoothstep(0.985, 1.0, light.x);
    float glow = velo_emissive_glow() * self;
    if (glow > 0.0) {
        float peak = max(tex.r, max(tex.g, tex.b));
        float mask = smoothstep(0.4, 0.85, peak);
        float flicker = 0.9 + 0.1 * velo_noise2(vec2(VeloCam.w * 6.0, peak * 9.0));
        rgb += albedo * albedo * mask * glow * 1.1 * flicker;
        float rim = pow(1.0 - clamp(abs(dot(N, normalize(-relPos))), 0.0, 1.0), 2.5);
        rgb += albedo * rim * glow * 0.55;
        rgb = max(rgb, albedo * 0.85);
    }
    return rgb;
}

// Glowing layers (spider and enderman eyes, the warden's spots, the creaking's eyes...).
vec3 velo_entity_emissive(vec3 rgb) {
    return rgb * (1.0 + velo_emissive_glow() * 0.6);
}
