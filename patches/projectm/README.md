# projectM patches

Applied to `third_party/projectm` (v4.1.8) by `app/src/main/cpp/CMakeLists.txt` before it builds projectM.
Later patches change lines of earlier ones: CMake resets `src/` and applies them all in order unless the last one is
already in. The submodule then shows as modified; that is expected. `git -C third_party/projectm checkout -- src` undoes them.

- `0001-batch-custom-shape-draws.patch`: draws custom shapes in batches instead of one draw call and buffer
  update per instance. Backport to the 4.1 renderer of upstream pull request
  [#1044](https://github.com/projectM-visualizer/projectm/pull/1044) (fa7ad), which targets `master`. Drop this
  patch once the app moves to a projectM release that includes #1044.
- `0002-batch-non-additive-shape-borders.patch` (ours, on top of 0001): borders of untextured, non-additive
  shapes go into the same triangle list as the fills, as 1-pixel-wide quads, so the draw order stays exact without
  a draw call per instance. Corners of semi-transparent borders can blend twice where two edge quads meet.
  On the TV at 480p: phlegmployant 12.1 to 28.1 fps, ants on acid 30.9 to 38.5. Candidate for upstream, after #1044.
