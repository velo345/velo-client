// Thunderstrike - violet storm clouds and rain; forked lightning tears down behind the sharp Velo logo and lights the
// whole sky (and the logo's face) twice a loop.

const vec3 GLOW = vec3(1.15, 0.38, 2.3);
const vec3 HOT = vec3(2.1, 1.9, 2.7);
const vec3 FACE0 = vec3(0.005, 0.003, 0.012);
const vec3 FACE1 = vec3(0.06, 0.03, 0.14);
const vec3 AMB0 = vec3(0.01, 0.005, 0.02);
const vec3 AMB1 = vec3(0.05, 0.03, 0.09);
const vec3 FABRIC = vec3(0.012, 0.008, 0.024);
const vec3 STORM_DARK = vec3(0.025, 0.015, 0.05);
const vec3 STORM_LIT = vec3(0.45, 0.3, 0.95);
const vec2 LOGO_C = vec2(0.0, 0.8);
const float LOGO_S = 8.6;
const float LOGO_TH = 0.36;
const float LOGO_CH = 0.13;
#define OVERLAY

#include "kit.glsl"
#include "storm.glsl"
