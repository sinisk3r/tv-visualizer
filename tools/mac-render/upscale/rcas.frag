// FSR 1 RCAS (Robust Contrast Adaptive Sharpening), ported from AMD FidelityFX ffx_fsr1.h v1.20210629, FsrRcasF
// (non-packed 32-bit path, no denoise, no alpha passthrough). MIT licence, Copyright (c) 2021 Advanced Micro
// Devices, Inc.; full text in README.md. Runs at output size on the EASU result.
// Changes from the original: per-channel scalar code written as vec3; the two limiter divisions get a tiny guard
// so all-black or all-white neighbourhoods give 0/-x instead of 0/0 (GLSL max() with NaN is undefined; visualizer
// frames are mostly black).
// Uniform uSharp = FsrRcasCon con[0] as a float: exp2(-stops), 1.0 = maximum sharpness.
uniform sampler2D uSrc;
uniform float uSharp;
out vec4 fragColor;

#define FSR_RCAS_LIMIT (0.25 - (1.0 / 16.0))

float rcpMed(float a) {  // APrxMedRcpF1
    float b = uintBitsToFloat(0x7ef19fffu - floatBitsToUint(a));
    return b * (-b * a + 2.0);
}

vec3 load(ivec2 p) {
    ivec2 s = textureSize(uSrc, 0);
    return texelFetch(uSrc, clamp(p, ivec2(0), s - 1), 0).rgb;
}

void main() {
    //    b
    //  d e f
    //    h
    ivec2 sp = ivec2(gl_FragCoord.xy);
    vec3 b = load(sp + ivec2(0, -1));
    vec3 d = load(sp + ivec2(-1, 0));
    vec3 e = load(sp);
    vec3 f = load(sp + ivec2(1, 0));
    vec3 h = load(sp + ivec2(0, 1));
    // Min and max of ring.
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    // Immediate constants for peak range.
    vec2 peakC = vec2(1.0, -1.0 * 4.0);
    // Limiters.
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0e-6));
    vec3 hitMax = (peakC.x - max(mx4, e)) / min(4.0 * mn4 + peakC.y, vec3(-1.0e-6));
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-FSR_RCAS_LIMIT, min(max(max(lobeRGB.r, lobeRGB.g), lobeRGB.b), 0.0)) * uSharp;
    // Resolve, which needs the medium precision rcp approximation to avoid visible tonality changes.
    float rcpL = rcpMed(4.0 * lobe + 1.0);
    fragColor = vec4((lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL, 1.0);
}
