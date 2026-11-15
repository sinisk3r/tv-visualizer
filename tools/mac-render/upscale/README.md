# Upscale shaders

GLSL ports of AMD FidelityFX upscalers, used by `tools/mac-render/upscale-compare.cpp` to test whether a sharpening
stretch lets projectM render smaller (docs/next.md S7). Written to run unchanged on desktop GL 3.3 core and GLES 3.0:
the host prepends `#version 330 core` or `#version 300 es` plus `precision highp float; precision highp int;`.
No textureGather (GLES 3.1+); every tap is a `texelFetch`.

| File | What | Source |
|---|---|---|
| `fullscreen.vert` | one triangle covering the target, no vertex buffer | ours |
| `cas.frag` | CAS with scaling (one pass): sharpen + resize | `ffx_cas.h` 2019 (FidelityFX-CAS), non-packed `CasFilter`, scaling path |
| `easu.frag` | FSR 1 EASU (edge-adaptive upscale) | `ffx_fsr1.h` v1.20210629 (FidelityFX-FSR), `FsrEasuF` |
| `rcas.frag` | FSR 1 RCAS (sharpen at output size, after EASU) | `ffx_fsr1.h` v1.20210629, `FsrRcasF` |

Uniforms are the float form of the CPU setup functions (`CasSetup`, `FsrEasuCon`, `FsrRcasCon`); see the host.

Licence of the AMD code (MIT):

    Copyright (c) 2019-2021 Advanced Micro Devices, Inc. All rights reserved.
    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
    documentation files (the "Software"), to deal in the Software without restriction, including without limitation
    the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
    permit persons to whom the Software is furnished to do so, subject to the following conditions:
    The above copyright notice and this permission notice shall be included in all copies or substantial portions of
    the Software.
    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
    THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
    TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
