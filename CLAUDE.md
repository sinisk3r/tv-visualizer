# Visualizer: notes for Claude

MilkDrop-style music visualizer for Android TV (projectM 4 via the NDK). Public repo, MIT. The README covers
features, build and licences; this file covers how to work on it. Keep it under ~80 lines.

## Layout
- `app/src/main/java/io/github/sinisk3r/visualizer/`
  - `MainActivity`: keys, audio capture (`Visualizer` session 0), overlay, starts visuals after the preset scan.
  - `VizRenderer`: GL thread. Play order, style filters, Auto picture, fps log. Reached only via `queueEvent`.
  - `MenuPanel`: the OK menu: transport bar + cards (settings, live and advanced, find, about). Built once
    (Find rows once per page); `refresh()`/`tick()` only rewrite values. `BarText`: its texts, with tests.
  - `PresetLibrary` (scan, styles, sub-styles), `PackDownloader` (full pack), `Settings` (prefs), `Native` (JNI).
- `app/src/main/cpp/native.cpp`: one global projectM instance; callbacks return as bit flags from `render()`.
- `tools/`: `fetch-packs.sh`, `make-bundle.py` (bundled presets), `make-heavy-list.py` (assets/heavy.txt),
  `make-reactivity-list.py` (assets/reactivity.txt: audio use, play weights), `make-numbers.py` (assets/numbers.txt:
  fixed channel numbers; never renumber, only append),
  `colour-logo.py` (needs Pillow + numpy).
- `docs/performance.md`: what makes presets slow on TVs, measurements, the planned projectM shape fix.
- `third_party/projectm`: submodule at v4.1.8, with `patches/projectm/*.patch` applied by CMake at build time (so
  it shows as modified). Preset/texture packs in `third_party/presets-*` are gitignored.

## Build and test
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease`
  (NDK 27.2.12479018, CMake 3.22.1). Then `adb install -r app/build/outputs/apk/release/app-release.apk`.
- Test on a real TV over wireless ADB. Logs: `adb logcat -s Visualizer` (fps every 10 s, Auto picture steps).
- Speed on the TV: `tools/tv-bench.sh 480` runs the 5-preset set in `tools/bench-presets.txt` (~2.5 min); or pass
  preset paths. Holds each at 480p, ignoring filters (test hook: `am start ... --es preset <path> --ei height 480`).
  Run one bench at a time.
- No music app playing: `am start ... --ez demo true` plays the demo songs (streamed; Kevin MacLeod, CC BY).
- Grant audio without the dialog: `adb shell pm grant io.github.sinisk3r.visualizer android.permission.RECORD_AUDIO`.
- Drive the remote one key per `adb shell input keyevent <code>` call with ~0.5 s between; batched key lists
  have toggled the wrong rows. Screenshot: `adb exec-out screencap -p > shot.png`.
- `adb shell am start -W` can hang when the TV's screensaver takes over; use plain `am start`.
- Release builds are not debuggable: `run-as` cannot read prefs. Log what you need instead (learned heights
  are logged at start: `adb logcat -s Visualizer | grep learned`).
- After editing `CMakeLists.txt`, `rm -rf app/.cxx`: a reconfigure leaves projectM's `version.h` empty.

## Tests (every change)
- `./gradlew testDebugUnitTest` (JUnit + Robolectric, `app/src/test`) and `python3 -m unittest discover -s
  tools/tests`. CI runs both on every push (`.github/workflows/tests.yml`).
- UI (`app/src/androidTest`, emulator only): `./gradlew assembleDebug assembleDebugAndroidTest`, install both APKs
  with `adb -s emulator-5554`, then `am instrument -w io.github.sinisk3r.visualizer.test/androidx.test.runner.AndroidJUnitRunner`
  (not `connectedAndroidTest`: it installs on every device, the TV too).
- New logic comes with a test in the same commit; a bug fix comes with a test that failed before it.

## Testing off the TV (the TV is often busy)
- Test on the Mac first, the TV last. The TV result is the only one that counts for speed.
- Speed: a native macOS projectM harness (build it if missing) that renders presets offscreen and reports CPU
  ms/frame, draw calls and buffer updates. Counts are the same on any machine; timings are not.
- Device profiles ("this TV", "weaker TV"): don't emulate them. Calibrate instead: run the same presets on the Mac
  and on the TV (learned heights and fps logs), fit Mac ms to TV fps, then predict. Re-check on the TV.
- Parallel ideas: one git worktree each. Run count checks in parallel; run timings one at a time, since
  parallel runs share CPU and skew each other.
- UI and themes: HTML mockups at 1920x1080 first, then the Android TV emulator (AVD `tv34`, Google TV, API 34,
  same Android as the TV) for layout and focus. The emulator draws on the Mac GPU: never use it for fps.

## Rules learned the hard way
- Never move or scale views over the GLSurfaceView (slide-ins, focus zoom): composition clips them. Fade only.
- Make a view VISIBLE before requesting focus in it; a hidden view cannot take focus and keys fall to the visuals.
- Never rebuild menu rows for a value change (progress, preset change): it flickers and resets focus.
- Keys are handled in `dispatchKeyEvent`: the GL view is clickable and would eat OK as a click.
- No projectM teardown in `onDestroy`: after `recreate()` the old GL thread could kill the new instance.
  `Native.create()` frees any previous instance.
- Bump `versionCode` when the bundled assets change, or installed apps keep the old unpacked copy.
- Raise `Settings.RENDERER_GENERATION` when a change makes presets faster or slower (a projectM patch, renderer
  work): installed apps then forget learned picture sizes and the too-slow list, and measure again.
- Auto picture must not count frames during a preset blend (3 s) or right after a load/resize (1.5 s).
- Preset scanning is slow on TV storage (~1-2 s for 10k files): keep it off the main thread.
- Hide views (GONE) when not shown: any visible view, even at alpha 0, makes the TV GPU compose the full
  4K frame each vsync (`dumpsys SurfaceFlinger`: CLIENT, not DEVICE).

## Art
- The logo is AI-generated; prompts in `art/prompts.md`. Credit them only as "AI-generated".
- Make variants locally (`tools/colour-logo.py`, `sips`) rather than generating new images.
- The menu stays neutral grey; colour belongs to the visuals and the icon.

## Publishing
- Commits use the GitHub noreply address (repo-local git config). Never commit device IPs, secrets or
  personal details: the repo is public.
- Before a store release: real signing key (now the debug key), and decide on the bundled preset count.
