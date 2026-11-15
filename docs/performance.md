# Performance on TV hardware

Measured 15-Nov-2026 on a TCL Smart TV Pro: MediaTek "merak", 4 x Cortex-A73 at 1.39 GHz, Mali-G52
(OpenGL ES 3.2, driver r44p1), 2.4 GB RAM, 4K panel at 60 Hz. projectM 4.1.8. Logs: `adb logcat -s Visualizer`.

## What costs frames

| Cost | Where it lands | Does a smaller picture help? |
|---|---|---|
| Custom shapes with many instances (up to 4 x 1024) | CPU: equations per instance, plus a shader bind, a buffer update and 1-3 draw calls each | No |
| Custom waves with per-point code (samples x lines) | CPU | No |
| Blur passes (`GetBlur1/2/3`), texture reads, long warp/comp shaders | GPU fill rate | Yes |
| Anything drawn over the visuals (menu, preset name) | The TV composes the full 4K frame on the GPU instead of in display hardware | Partly |
| Loading a preset | CPU on the render thread: warp + composite shader compile | No |

RAM is not the limit: the app uses ~217 MB RSS. MilkDrop was written for desktop CPUs several times faster per
core than a TV's, and with far cheaper draw calls under Direct3D 9.

## Evidence

**Shapes.** Auto picture learned a height for 128 presets. Median custom-shape instances by learned height:
480p (slowest) 344, 540p 2, 720p 0, 1080p 0. 21 of the 38 presets at 480p have 200+ instances. Example:
"cope - ants on acid orchestrate chaos - flx roam i hate myself" draws 200 + 33 + 1024 = 1,257 shapes per frame
and ran 7-10 fps at 480p. The render thread used 73% of a core and the Mali driver threads another ~83%. In the
full pack, 1,654 of 9,795 presets have 200+ instances (`app/src/main/assets/heavy.txt`).

**Waves.** Many slow presets without shapes have heavy per-point waves (e.g. 1,024 samples x 75 lines), but
presets with similar waves also ran fine at 720p, so waves alone do not predict slowness well.

**Composition.** `adb shell dumpsys SurfaceFlinger`, HWC layers: with only the visuals on screen the layer is
`DEVICE` (display hardware scales 854x480 to 3840x2160 for free). With any app view visible, even at alpha 0,
both layers become `CLIENT` and the GPU composes 4K every frame. A held preset dropped from 60 to ~40 fps with
the menu open. Fix in the app: the preset name goes `GONE` after fading, and Auto picture does not measure while
anything covers the visuals.

**Preset loads.** Timed inside projectM: parse ~10-15 ms, expression compile ~2-10 ms, warp shader 12-93 ms,
composite shader 12-130 ms. Loads took 22-330 ms in total, all on the render thread. The GPU driver caches
compiled shaders for the process (a revisited preset went from 103 to 35 ms), so `native.cpp` runs a second,
64x36 projectM on its own thread and GL context and loads the next preset there first. First-time loads then
take ~17-80 ms. It skips presets that use `gmegabuf`, which projectM shares between instances without a lock.

## The planned projectM fix: batch custom shapes

In `src/libprojectM/MilkdropPreset/CustomShape.cpp` (v4.1.8), `Draw()` loops over instances, and each one binds a
shader, calls `glBufferSubData` on the same buffer, and draws a triangle fan, then a line loop for the border
(4 times for thick borders). On mobile drivers, rewriting a buffer that an earlier draw in the same frame still
uses forces a copy or a stall.

Plan: build every instance's vertices into one buffer per frame (fans as triangles, borders as lines), then
draw each run of instances that share state (textured, additive, thick) with one call. Upstream `master` has
reworked this file since 4.1.8 (`6095f3228 Use Mesh class in Custom Shape effect`), so write the change against
`master` for an upstream pull request, then backport it to the version the app builds. Search the upstream issues
first for earlier work. Measure with the "ants on acid" preset held, at 480p.
