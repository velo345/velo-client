#version 330

// Velo Shaders - depth-buffer helpers for post passes; import after declaring InDepthSampler and InDepthSize.

vec3 view_at(vec2 uv) {
    return view_pos(uv, texture(InDepthSampler, uv).r);
}

// Normal from the depth buffer: of the two neighbours on each axis, take the closer one - no
// smearing across silhouette edges.
vec3 view_normal(vec2 uv, vec3 p) {
    vec2 px = 1.0 / InDepthSize;
    vec3 r = view_at(uv + vec2(px.x, 0.0));
    vec3 l = view_at(uv - vec2(px.x, 0.0));
    vec3 u = view_at(uv + vec2(0.0, px.y));
    vec3 d = view_at(uv - vec2(0.0, px.y));
    vec3 dx = abs(r.z - p.z) < abs(p.z - l.z) ? r - p : p - l;
    vec3 dy = abs(u.z - p.z) < abs(p.z - d.z) ? u - p : p - d;
    vec3 n = normalize(cross(dx, dy));
    return dot(n, p) > 0.0 ? -n : n;
}

// Blocks are axis-aligned: snap a reconstructed normal to its block axis when it's close to one.
// At the hairline seams between block faces the depth normal flips for a pixel, which put dark
// AO lines along every block edge.
vec3 view_normal_snapped(vec3 nView) {
    vec3 w = (VeloInvView * vec4(nView, 0.0)).xyz;
    vec3 a = abs(w);
    float m = max(a.x, max(a.y, a.z));
    if (m > 0.7) {
        w = a.x == m ? vec3(sign(w.x), 0.0, 0.0) : a.y == m ? vec3(0.0, sign(w.y), 0.0) : vec3(0.0, 0.0, sign(w.z));
        return normalize((VeloView * vec4(w, 0.0)).xyz);
    }
    return nView;
}
