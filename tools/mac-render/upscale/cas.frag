// CAS (Contrast Adaptive Sharpening) with scaling, one pass. Ported from AMD FidelityFX ffx_cas.h (2019),
// CasFilter, non-packed path with scaling, default defines (no CAS_BETTER_DIAGONALS, no CAS_SLOW, no
// CAS_GO_SLOWER). MIT licence, Copyright (c) 2017-2019 Advanced Micro Devices, Inc.; full text in README.md.
// Changes from the original: per-channel scalar code written as vec3; the corner taps a, d, m, p are not loaded
// (only CAS_BETTER_DIAGONALS uses them), so 12 texelFetch calls, clamped to the image edge.
// AMD supports scaling up to 4x area (CAS_AREA_LIMIT); 480p to 1080p is 5.1x, outside that.
// Uniforms = CasSetup as floats: uCon0 = (inW/outW, inH/outH, 0.5*inW/outW-0.5, 0.5*inH/outH-0.5);
// uPeak = -1/mix(8, 5, sharpness), sharpness 0 (default, least ringing) to 1 (most).
uniform sampler2D uSrc;
uniform vec4 uCon0;
uniform float uPeak;
out vec4 fragColor;

vec3 rcpLo(vec3 a) { return uintBitsToFloat(0x7ef07ebbu - floatBitsToUint(a)); }  // APrxLoRcpF1
float rcpLo(float a) { return uintBitsToFloat(0x7ef07ebbu - floatBitsToUint(a)); }
float rcpMed(float a) {  // APrxMedRcpF1
    float b = uintBitsToFloat(0x7ef19fffu - floatBitsToUint(a));
    return b * (-b * a + 2.0);
}
vec3 sqrtLo(vec3 a) { return uintBitsToFloat((floatBitsToUint(a) >> 1u) + 0x1fbc4639u); }  // APrxLoSqrtF1

vec3 load(ivec2 p) {
    ivec2 s = textureSize(uSrc, 0);
    return texelFetch(uSrc, clamp(p, ivec2(0), s - 1), 0).rgb;
}

vec3 min5(vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) { return min(min(min(a, b), c), min(d, e)); }
vec3 max5(vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) { return max(max(max(a, b), c), max(d, e)); }

void main() {
    vec2 pp = floor(gl_FragCoord.xy) * uCon0.xy + uCon0.zw;
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 sp = ivec2(fp);
    //  a b c d
    //  e f g h
    //  i j k l
    //  m n o p
    vec3 b = load(sp + ivec2(0, -1)), c = load(sp + ivec2(1, -1));
    vec3 e = load(sp + ivec2(-1, 0)), f = load(sp), g = load(sp + ivec2(1, 0)), h = load(sp + ivec2(2, 0));
    vec3 i = load(sp + ivec2(-1, 1)), j = load(sp + ivec2(0, 1)), k = load(sp + ivec2(1, 1)), l = load(sp + ivec2(2, 1));
    vec3 n = load(sp + ivec2(0, 2)), o = load(sp + ivec2(1, 2));
    // Soft min and max of the cross around each of f, g, j, k.
    vec3 mnf = min5(b, e, f, g, j), mxf = max5(b, e, f, g, j);
    vec3 mng = min5(c, f, g, h, k), mxg = max5(c, f, g, h, k);
    vec3 mnj = min5(f, i, j, k, n), mxj = max5(f, i, j, k, n);
    vec3 mnk = min5(g, j, k, l, o), mxk = max5(g, j, k, l, o);
    // Smooth minimum distance to signal limit divided by smooth max.
    vec3 ampf = clamp(min(mnf, 1.0 - mxf) * rcpLo(mxf), 0.0, 1.0);
    vec3 ampg = clamp(min(mng, 1.0 - mxg) * rcpLo(mxg), 0.0, 1.0);
    vec3 ampj = clamp(min(mnj, 1.0 - mxj) * rcpLo(mxj), 0.0, 1.0);
    vec3 ampk = clamp(min(mnk, 1.0 - mxk) * rcpLo(mxk), 0.0, 1.0);
    // Shaping amount of sharpening.
    ampf = sqrtLo(ampf);
    ampg = sqrtLo(ampg);
    ampj = sqrtLo(ampj);
    ampk = sqrtLo(ampk);
    // Filter shape: 0 w 0 / w 1 w / 0 w 0. Only green weights are used (no CAS_SLOW).
    float wf = ampf.g * uPeak, wg = ampg.g * uPeak, wj = ampj.g * uPeak, wk = ampk.g * uPeak;
    // Blend between 4 results.
    float s = (1.0 - pp.x) * (1.0 - pp.y);
    float t = pp.x * (1.0 - pp.y);
    float u = (1.0 - pp.x) * pp.y;
    float v = pp.x * pp.y;
    // Thin edges to hide bilinear interpolation (helps diagonals).
    float thinB = 1.0 / 32.0;
    s *= rcpLo(thinB + (mxf.g - mnf.g));
    t *= rcpLo(thinB + (mxg.g - mng.g));
    u *= rcpLo(thinB + (mxj.g - mnj.g));
    v *= rcpLo(thinB + (mxk.g - mnk.g));
    // Final weighting.
    float qbe = wf * s;
    float qch = wg * t;
    float qf = wg * t + wj * u + s;
    float qg = wf * s + wk * v + t;
    float qj = wf * s + wk * v + u;
    float qk = wg * t + wj * u + v;
    float qin = wj * u;
    float qlo = wk * v;
    float rcpW = rcpMed(2.0 * qbe + 2.0 * qch + 2.0 * qin + 2.0 * qlo + qf + qg + qj + qk);
    vec3 pix = (b * qbe + e * qbe + c * qch + h * qch + i * qin + n * qin + l * qlo + o * qlo + f * qf + g * qg + j * qj +
                k * qk) * rcpW;
    fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);
}
