# Visualizer

MilkDrop-style music visuals for Android TV / Google TV (and any Android device), powered by
[projectM 4](https://github.com/projectM-visualizer/projectm). It reacts to whatever the device is playing
(Spotify, YouTube, ...) through `android.media.audiofx.Visualizer` on the output mix. No microphone, nothing
recorded or sent.

## Remote
| Key | Does |
|---|---|
| Left / Right | Previous / next preset |
| Up / Down | Change style (Dancer, Drawing, Fractal, ...) or All |
| OK / Menu | Open the menu (Back closes) |
| Info | Show the current preset name |
| Touch | Tap = next, long-press = menu |

Menu: Hold this preset (forever), Hide this preset, Time per preset (15 s to 5 min), Picture (Auto or a fixed
480p/540p/720p/1080p), Switch on big beats, one switch per style, Unhide all, Re-test picture sizes.

Presets switch every 30 s, or on a big beat (hard cut). Presets that fail to compile on the GPU are skipped.

## Presets
- 400 bundled: 40 per style, picked with a fixed seed from
  [Cream of the Crop](https://github.com/projectM-visualizer/presets-cream-of-the-crop) (9,795 presets),
  plus the [MilkDrop texture pack](https://github.com/projectM-visualizer/presets-milkdrop-texture-pack).
- More: put `.milk` files (in style folders) in `Android/data/io.github.sinisk3r.visualizer/files/presets/`,
  e.g. `adb push third_party/presets-cream-of-the-crop/. /sdcard/Android/data/io.github.sinisk3r.visualizer/files/presets/`.
  Extra textures go in `.../files/textures/`.

## Build
```sh
git submodule update --init --recursive   # projectM v4.1.8
tools/fetch-packs.sh                       # preset + texture packs (156 MB, not committed)
tools/make-bundle.py 40                    # copies the bundled pack into app/src/main/assets
./gradlew assembleRelease                  # needs NDK 27.2.12479018, CMake 3.22.1, JDK 17
adb install -r app/build/outputs/apk/release/app-release.apk
```
Release builds are signed with the debug key; set up a real signing key before publishing.

## Picture: Auto
Each preset renders below screen size and the display scales it up. In Auto (default) every preset learns its
own height on this TV: a 3 s window under 24 fps steps it down (1080p, 720p, 540p, 480p) and the choice is saved.
Frames during the 3 s blend between presets and 1.5 s after a load or resize are not counted (two presets render
at once during a blend; shader compiles stall the first frames). A lowered preset that holds 55+ fps for 10 s
tries one step up, once per session. A preset never seen before starts at the height most presets settled at.

## Performance (TCL G08, Mali-G52, 32-bit, 13-Nov-2026)
Fixed 540p with a 32x24 mesh: 30-60 fps, typically ~45. At 720p / 48x32: 13-42 fps. At 1080p many presets
run 12-22 fps (Auto drops them). RAM ~33 MB PSS. Logs: `adb logcat -s Visualizer` (fps every 10 s, auto steps).

## Licences
- This app's code and art (`app/`, `tools/`, `art/`): MIT, see `LICENSE`. The logo and menu art were made with
  ChatGPT image generation; prompts in `art/prompts.md`.
- projectM: LGPL-2.1, shipped as its own shared library (`libprojectM-4.so`) so it can be replaced.
- Presets and textures: from projectM's preset and texture repos. MilkDrop presets were released freely without a
  formal licence; projectM treats them as public domain and removes any on the author's request. Preset authors are
  credited in the file names. Ask and a preset is removed here too.
