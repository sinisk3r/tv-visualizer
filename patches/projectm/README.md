# projectM patches

Applied to `third_party/projectm` (v4.1.8) by `app/src/main/cpp/CMakeLists.txt` before it builds projectM.
The submodule then shows as modified; that is expected. `git -C third_party/projectm checkout -- src` undoes them.

- `0001-batch-custom-shape-draws.patch`: draws custom shapes in batches instead of one draw call and buffer
  update per instance. Backport to the 4.1 renderer of upstream pull request
  [#1044](https://github.com/projectM-visualizer/projectm/pull/1044) (fa7ad), which targets `master`. Drop this
  patch once the app moves to a projectM release that includes #1044.
