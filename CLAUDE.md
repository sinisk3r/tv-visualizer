# Visualizer: notes for Claude

MilkDrop-style music visualizer for Android TV (projectM 4 via the NDK). Public repo, MIT. The README covers
features, build and licences; this file covers how to work on it. Keep it under ~80 lines.

## Layout
- `app/src/main/java/io/github/sinisk3r/visualizer/`
  - `MainActivity`: keys, audio capture (`Visualizer` session 0), overlay, starts visuals after the preset scan.
  - `VizRenderer`: GL thread. Play order, style filters, Auto picture, fps log. Reached only via `queueEvent`.
  - `MenuPanel`: the OK menu (main page + one page per style). Rows built once per page; `refresh()` only
    rewrites values.
  - `PresetLibrary` (scan, styles, sub-styles), `PackDownloader` (full pack), `Settings` (prefs), `Native` (JNI).
- `app/src/main/cpp/native.cpp`: one global projectM instance; callbacks return as bit flags from `render()`.
- `tools/`: `fetch-packs.sh`, `make-bundle.py` (bundled presets), `make-heavy-list.py` (assets/heavy.txt),
  `colour-logo.py` (needs Pillow + numpy).
- `docs/performance.md`: what makes presets slow on TVs, measurements, the planned projectM shape fix.
- `third_party/projectm`: submodule at v4.1.8. Preset/texture packs in `third_party/presets-*` are gitignored.

## Build and test
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease`
  (NDK 27.2.12479018, CMake 3.22.1). Then `adb install -r app/build/outputs/apk/release/app-release.apk`.
- Test on a real TV over wireless ADB. Logs: `adb logcat -s Visualizer` (fps every 10 s, Auto picture steps).
- Grant audio without the dialog: `adb shell pm grant io.github.sinisk3r.visualizer android.permission.RECORD_AUDIO`.
- Drive the remote one key per `adb shell input keyevent <code>` call with ~0.5 s between; batched key lists
  have toggled the wrong rows. Screenshot: `adb exec-out screencap -p > shot.png`.
- `adb shell am start -W` can hang when the TV's screensaver takes over; use plain `am start`.
- Release builds are not debuggable: `run-as` cannot read prefs. Log what you need instead (learned heights
  are logged at start: `adb logcat -s Visualizer | grep learned`).
- After editing `CMakeLists.txt`, `rm -rf app/.cxx`: a reconfigure leaves projectM's `version.h` empty.

## Rules learned the hard way
- Never move or scale views over the GLSurfaceView (slide-ins, focus zoom): composition clips them. Fade only.
- Make a view VISIBLE before requesting focus in it; a hidden view cannot take focus and keys fall to the visuals.
- Never rebuild menu rows for a value change (progress, preset change): it flickers and resets focus.
- Keys are handled in `dispatchKeyEvent`: the GL view is clickable and would eat OK as a click.
- No projectM teardown in `onDestroy`: after `recreate()` the old GL thread could kill the new instance.
  `Native.create()` frees any previous instance.
- Bump `versionCode` when the bundled assets change, or installed apps keep the old unpacked copy.
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
