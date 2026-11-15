# Visualizer

MilkDrop-style music visuals for Android TV / Google TV (and any Android device), powered by
[projectM 4](https://github.com/projectM-visualizer/projectm). It reacts to whatever the device is playing
(Spotify, YouTube, ...) through `android.media.audiofx.Visualizer` on the output mix. No microphone, nothing
recorded or sent.

## Remote
| Key | Does |
|---|---|
| Left / Right | Previous / next preset (1.5 s blend) |
| Up / Down | Change style: All, ★ Favourites, Dancer, Drawing, Fractal, ... |
| OK / Menu | Open the menu (Back closes) |
| Info | Show the current preset name |
| Touch | Tap = next, long-press = menu |

Menu (left side; Up/Down moves, OK toggles or opens, Left/Right changes a value, Back goes back):
- Hold this preset (forever), ★ Favourite, Hide this preset
- Time per preset (15 s to 5 min), Picture (Smooth or Sharp auto, or a fixed 480p/540p/720p/1080p), Switch on big beats,
  Skip heavy presets, Skip deaf presets
- Styles: each style opens a page with "Play this style" and one switch per sub-style
- Presets: Get all 9,795 presets, Favourites, Unhide all, Re-test picture sizes

Presets switch every 30 s (3 s blend), or on a big beat (hard cut). Presets that fail to compile on the GPU are
skipped. While a preset plays, the next one's shaders compile on a background thread, so the switch does not
freeze the picture.

Skip heavy presets (on by default) leaves out presets that crawl on TV hardware whatever the picture size:
- 197 listed in `app/src/main/assets/heavy.txt` (`tools/make-heavy-list.py`): 500+ custom shape instances that are
  still drawn one at a time (non-additive shapes with a border). Other shapes are drawn in batches since the
  projectM patch in `patches/projectm/` (docs/performance.md).
- Any preset Auto picture measures under 20 fps at 480p on this TV. "Re-test picture sizes" forgets these too.

Presets are scored by how much they follow the music (`app/src/main/assets/reactivity.txt`, from
`tools/make-reactivity-list.py`): audio traced from bass/mid/treb through the equations and shaders to what is
drawn, plus visible waveforms. Of the full pack, 442 react to nothing (Skip deaf presets, on by default, leaves
them out), 658 move only a waveform (played half as often) and the top quarter, 2,279, play twice as often.

## Presets
- 400 bundled: 40 per style, picked with a fixed seed from
  [Cream of the Crop](https://github.com/projectM-visualizer/presets-cream-of-the-crop) (9,795 presets),
  plus the [MilkDrop texture pack](https://github.com/projectM-visualizer/presets-milkdrop-texture-pack).
- Full pack: menu > Presets > Get all 9,795 presets. Downloads a 33 MB zip from GitHub (pinned commit in
  `PackDownloader.COMMIT`), unpacks ~156 MB of `.milk` files into the app's files folder, then restarts the app.
- Sorted two levels deep: style (top folder) and sub-style (folder under it), e.g. Geometric > Cathedral.
  10 styles, 183 sub-styles in the full pack.
- Your own: put `.milk` files as `Style/Sub-style/name.milk` in
  `Android/data/io.github.sinisk3r.visualizer/files/presets/` (adb push or a file manager). Extra textures go in
  `.../files/textures/`. A preset found in two places counts once.

## Build
```sh
git submodule update --init --recursive   # projectM v4.1.8
tools/fetch-packs.sh                       # preset + texture packs (156 MB, not committed)
tools/make-bundle.py 40                    # 40 per style into app/src/main/assets (keeps sub-style folders)
tools/make-heavy-list.py                   # app/src/main/assets/heavy.txt from the full pack
tools/make-reactivity-list.py              # app/src/main/assets/reactivity.txt from the full pack
./gradlew assembleRelease                  # needs NDK 27.2.12479018, CMake 3.22.1, JDK 17
adb install -r app/build/outputs/apk/release/app-release.apk
```
Release builds are signed with the debug key; set up a real signing key before publishing.
Bump `versionCode` whenever the bundled pack changes: the app re-unpacks it only on a new version.

## Picture: Auto
Each preset renders below screen size and the display scales it up. In Auto (default) every preset learns its
own height on this TV: a 3 s window under 24 fps steps it down (1080p, 720p, 540p, 480p) and the choice is saved.
Frames during the 3 s blend between presets and 1.5 s after a load or resize are not counted (two presets render
at once during a blend; shader compiles stall the first frames). A lowered preset that holds 55+ fps for 10 s
tries one step up, once per session. A preset never seen before starts at the height most presets settled at.
Nothing is measured while the menu or the preset name is on screen (see `docs/performance.md`).

## Performance (TCL G08, Mali-G52, 32-bit, 13-Nov-2026)
Fixed 540p with a 32x24 mesh: 30-60 fps, typically ~45. At 720p / 48x32: 13-42 fps. At 1080p many presets
run 12-22 fps (Auto drops them). RAM ~33 MB PSS. Logs: `adb logcat -s Visualizer` (fps every 10 s, auto steps).
What makes a preset slow, and measurements: `docs/performance.md`.

## Licences
- This app's code and art (`app/`, `tools/`, `art/`): MIT, see `LICENSE`. The logo is
  AI-generated (prompts in `art/prompts.md`); the colour logo is the grey one recoloured by `tools/colour-logo.py`.
- projectM: LGPL-2.1, shipped as its own shared library (`libprojectM-4.so`) so it can be replaced.
- Presets and textures: from projectM's preset and texture repos. MilkDrop presets were released freely without a
  formal licence; projectM treats them as public domain and removes any on the author's request. Preset authors are
  credited in the file names. Ask and a preset is removed here too.
