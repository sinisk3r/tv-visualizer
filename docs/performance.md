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

## The projectM fix: batched custom shapes (done 15-Nov-2026)

`patches/projectm/0001-batch-custom-shape-draws.patch`, applied to the v4.1.8 submodule at build time, backports
upstream pull request [#1044](https://github.com/projectM-visualizer/projectm/pull/1044) (open, targets `master`):
consecutive instances with the same blend mode and texturing become one triangle list and one draw call, and the
borders of additive instances one line list. Non-additive borders still draw per instance, in order.

Measured on this TV, preset held at 480p, `tools/tv-bench.sh 480 <preset>` (one 10 s window after a 10 s settle):

| Preset | Shapes per frame | Before (fps) | After (fps) |
|---|---|---|---|
| cope - ants on acid orchestrate chaos - flx roam i hate myself | 1,257 | 9.9 | 30.9 |
| martin - amandio c - a different view of the green machine (additive, thick borders) | 809 | 15.5 | 54.8 |
| suksma - phlegmployant (non-additive borders) | 1,046 | 7.2 | 12.1 |
| suksma - big swede (additive fills) | 512 | 22.8 | 60.0 |
| amandio c - interference pattern 2 (plain fills) | 501 | 23.8 | 39.4 |

Then `0002-batch-non-additive-shape-borders.patch` (ours): borders of untextured, non-additive shapes as 1-pixel
quads in the same triangle list. Same bench: phlegmployant 12.1 to 28.1 fps, ants on acid 30.9 to 38.5, the others
unchanged (green machine 51.0, interference 39.1, splash 40.1). What is left: borders of textured non-additive
shapes, and the per-instance equations on the CPU. `heavy.txt` now lists presets by unbatched instances (non-additive shapes with a border) instead of all
instances: 197 presets at 500+, down from 1,654 at 200+ instances in all. From 23 presets measured at 480p after
the fix: 1,000+ unbatched ran 10-12 fps, 200-300 ran 29-31 fps, 100 or fewer 39-60 fps.

## Tried and dropped

- CPU performance hints (15-Nov-2026): a `PerformanceHintManager` session on the GL thread, 16.7 ms target,
  reporting each frame's work. 480p bench set, hints off / on: ants 40.5 / 40.6, phlegmployant 27.4 / 27.7,
  green machine 51.5 / 51.2, interference 39.1 / 39.1, splash 40.1 / 40.2 fps. No gain: frames wait on the GPU,
  not the CPU clock.
- Coarser per-pixel mesh (15-Nov-2026), 16x12 vs 32x24 at 480p: phlegmployant 27.4 to 30.1 fps, ants on acid
  40.5 to 41.8, interference pattern 2 39.1 to 39.3. Helps only presets whose per-pixel equations load the CPU;
  not adopted yet (image cost not judged).

## Variable resolution, step 1 (15-Nov-2026)

Patch 0003 (backport of upstream `bd2b1ba9f`) lets projectM draw into our own texture at any size; `native.cpp`
stretches it onto the surface with `glBlitFramebuffer` (linear). TV, Mali-G52, fps (pacing off):

| | ants on acid | green machine | interference 2 |
|---|---|---|---|
| 480p surface, direct (today) | 41.8 | 54.7 | 39.4 |
| 480p inside, 1080p surface | 39.8 | 54.7 | 35.2 |
| 480p inside, 720p surface | 42.3 | | 37.4 |
| 540p inside, 720p surface | 36.0 | | 30.7 |

The stretch onto a 720p surface costs at most ~5%; onto 1080p up to ~11%. Plan: a 720p surface with the inner
size free below it, 1080p surface only for presets that run there. Next: keep the picture when the inner size
changes (projectM recreates its textures empty, so trails reset), then size steps every ~0.5 s.

## Variable resolution, step 3: the controller (15-Nov-2026)

`ResolutionController` picks projectM's inner height (Smooth: 360p-720p on a 720p surface; Sharp: 480p-1080p
on 1080p) every half second: a shrink jumps to the size the frame rate says fits; a step up waits 2 s of 58+
fps; a failed step goes back and holds 2 min; a shrink that gains under 5% means the CPU is the limit, so the
size goes back and climbs while each step is free (also for presets sitting at 55-57 fps). TV, Smooth, 45 s each
(`SECS=45 PACE=true tools/tv-bench.sh 0`), against a fixed 480p (direct, earlier today):

| preset | fixed 480p | variable (settled size) |
|---|---|---|
| ants on acid | 41.8 | 52.5 (416p) |
| phlegmployant | 27.4 | even 30, paced (416p) |
| green machine | 54.7 | 55.4 (552-576p, sharper) |
| interference pattern 2 | 39.4 | 56.2 (376p) |
| splash | 40.1 | 53.6 (376p) |

Sharp, first version (shrink below 24, grow at 30): presets sat at 24-30 fps, too slow for pacing (that needs
31+), so they ran an uneven ~25 and looked as if Sharp did not adjust. Now Sharp shrinks below 31, grows at
42+, aims at 36, and paces once settled (also at full size). TV, `PACE=true SECS=30 tools/tv-bench.sh 1`:
interference 36 fps at 520p, then even 30; a light Flexi preset climbed 592p to 704p, then even 30 at 39 fps.
Without pacing, all 5 bench presets settle at 31-35 fps between 488p and 712p.


## Sharpen the stretch: Mac check (15-Nov-2026)

`tools/mac-render/upscale-compare.sh` renders 8 presets (bench set + 3 thin-line waveforms) at 1080p, 720p and
480p (same moment: fake clock, fixed random numbers), upscales to 1080p with linear (today's blit), CAS (one
pass, sharpness 0.5) and FSR 1 (EASU + RCAS 0.2), and scores against the 1080p render. Mean of 8 presets:

| from | method | PSNR | SSIM | sharpness vs 1080p | Mac ms |
|---|---|---|---|---|---|
| 480p | linear | 23.4 | 0.737 | 0.74 | 0.32 |
| 480p | CAS | 23.1 | 0.724 | 0.92 | 0.52 |
| 480p | FSR 1 | 23.1 | 0.725 | 0.88 | 0.97 |
| 720p | linear | 26.4 | 0.784 | 0.83 | 0.42 |
| 720p | CAS | 26.2 | 0.775 | 1.00 | 0.63 |
| 720p | FSR 1 | 26.0 | 0.772 | 1.00 | 1.18 |

- projectM draws differently at each size, and that dwarfs any upscaler: lines keep a fixed pixel width (2.25x
  thicker after a 480p stretch), line waveforms take at most width/3 samples, feedback presets drift apart.
  No upscaler recovers that, so 480p + CAS does not match 720p (PSNR 23 vs 26), though its edges are crisper
  than linear 720p.
- Upscaler alone (1080p shrunk, then upscaled): CAS +1.3 dB at 480p, +2.1 dB at 720p over linear; FSR 1 in
  between at twice CAS's cost.
- Halos: at most ~1% of pixels near bright detail pushed past the input's local range (green machine's fine
  noise, the worst); thin lines show none worth seeing.
- Next: CAS only, as a look upgrade on the existing sizes, not a reason to lower them. Measure on the TV.
