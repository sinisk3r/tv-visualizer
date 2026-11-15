// FSR 1 EASU (Edge Adaptive Spatial Upsampling), ported from AMD FidelityFX ffx_fsr1.h v1.20210629, FsrEasuF
// (non-packed 32-bit path). MIT licence, Copyright (c) 2021 Advanced Micro Devices, Inc.; full text in README.md.
// Changes from the original: the 4 gathers are 12 texelFetch calls (GLES 3.0 has no textureGather), clamped to
// the image edge; per-channel scalar code written as vec3. Maths is otherwise line for line.
// Uniform uCon0 = FsrEasuCon con0 as floats: (inW/outW, inH/outH, 0.5*inW/outW-0.5, 0.5*inH/outH-0.5).
uniform sampler2D uSrc;
uniform vec4 uCon0;
out vec4 fragColor;

float rcpLo(float a) { return uintBitsToFloat(0x7ef07ebbu - floatBitsToUint(a)); }  // APrxLoRcpF1
float rsqLo(float a) { return uintBitsToFloat(0x5f347d74u - (floatBitsToUint(a) >> 1u)); }  // APrxLoRsqF1

vec3 load(ivec2 p) {
    ivec2 s = textureSize(uSrc, 0);
    return texelFetch(uSrc, clamp(p, ivec2(0), s - 1), 0).rgb;
}

// Luma times 2.
float luma2(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }

// FsrEasuSetF: accumulate direction and length for one of the 4 bilinear corners, weight w.
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = rcpLo(lenX);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;
    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = rcpLo(lenY);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

// FsrEasuTapF: one tap of the approximate Lanczos-2 kernel, rotated and stretched along the edge.
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v;
    v.x = (off.x * (dir.x)) + (off.y * dir.y);
    v.y = (off.x * (-dir.y)) + (off.y * dir.x);
    v *= len;
    float d2 = v.x * v.x + v.y * v.y;
    d2 = min(d2, clp);
    float wB = (2.0 / 5.0) * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void main() {
    vec2 pp = floor(gl_FragCoord.xy) * uCon0.xy + uCon0.zw;
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 f0 = ivec2(fp);
    // 12-tap kernel around f:
    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 b = load(f0 + ivec2(0, -1)), c = load(f0 + ivec2(1, -1));
    vec3 e = load(f0 + ivec2(-1, 0)), f = load(f0), g = load(f0 + ivec2(1, 0)), h = load(f0 + ivec2(2, 0));
    vec3 i = load(f0 + ivec2(-1, 1)), j = load(f0 + ivec2(0, 1)), k = load(f0 + ivec2(1, 1)), l = load(f0 + ivec2(2, 1));
    vec3 n = load(f0 + ivec2(0, 2)), o = load(f0 + ivec2(1, 2));
    float bL = luma2(b), cL = luma2(c), eL = luma2(e), fL = luma2(f), gL = luma2(g), hL = luma2(h);
    float iL = luma2(i), jL = luma2(j), kL = luma2(k), lL = luma2(l), nL = luma2(n), oL = luma2(o);
    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
    // Normalize with approximation, and clean up close to zero.
    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < (1.0 / 32768.0);
    dirR = rsqLo(dirR);
    dirR = zro ? 1.0 : dirR;
    dir.x = zro ? 1.0 : dir.x;
    dir *= vec2(dirR);
    // Transform from {0 to 2} to {0 to 1} range, and shape with square.
    len = len * 0.5;
    len *= len;
    // Stretch kernel {1.0 vert|horz, to sqrt(2.0) on diagonal}.
    float stretch = (dir.x * dir.x + dir.y * dir.y) * rcpLo(max(abs(dir.x), abs(dir.y)));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = rcpLo(lob);
    // Min and max of the 4 nearest, for deringing.
    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
    // Normalize and dering.
    fragColor = vec4(min(max4, max(min4, aC * (1.0 / aW))), 1.0);
}
