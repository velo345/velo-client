// Crimson Storm - blood-red storm clouds and rain; red lightning splits the sky behind the sharp Velo logo twice a loop.

const vec3 GLOW = vec3(2.2, 0.12, 0.1);
const vec3 HOT = vec3(2.6, 1.4, 1.2);
const vec3 FACE0 = vec3(0.01, 0.002, 0.002);
const vec3 FACE1 = vec3(0.13, 0.018, 0.02);
const vec3 AMB0 = vec3(0.02, 0.004, 0.004);
const vec3 AMB1 = vec3(0.08, 0.015, 0.015);
const vec3 FABRIC = vec3(0.022, 0.006, 0.007);
const vec3 STORM_DARK = vec3(0.05, 0.008, 0.01);
const vec3 STORM_LIT = vec3(0.95, 0.18, 0.15);
const vec2 LOGO_C = vec2(0.0, 0.8);
const float LOGO_S = 8.6;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define OVERLAY

#include "kit.glsl"
#include "storm.glsl"
