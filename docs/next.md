# Next work

The queue for this repo, top item first (the user's order: low effort, high value first; then the engine). Pick up with `/next-work` (or paste a prompt into a new session).
When an item is done, move it to "Done" with the date and commit. Add new items with a ready-to-paste prompt.
Read `CLAUDE.md` first, especially "Testing off the TV".

## D. Music feel: quick wins first (before A)

The user wants the visuals to follow the music much more closely. Order: low effort, high value first. The audio
feed fix is done (see Done). Ask the user first: what plays the sound (TV speakers, soundbar/ARC, Bluetooth),
and does it feel late, early, or not reacting? Check `adb logcat -s Visualizer | grep audio:` for level and gain.

```
Read CLAUDE.md and docs/next.md item D. Goal: the visuals feel locked to the music. Quick wins, in order:
1. Visual delay setting (menu row, -100..+300 ms, in 20 ms steps): delay the PCM fed to projectM by buffering
   captures, so beats line up with the user's speakers. Default 0. Test by ear with the user.
2. Reactivity: tools/ script that scores each preset's audio use (bass/mid/treb/_att/vol references in equations
   and shaders, waveform drawn, custom waves reading value1/value2). About 2% of the pack uses no audio at all
   (210 presets) and 3% only draw the waveform. Ship a list; skip the "no audio" ones by default (like heavy.txt)
   and play the strongly reactive ones more often.
3. Song-aware switching: switch preset on a song change (a gap of near-silence, or a sharp drop and rise in the
   slow AGC level in native.cpp) and on big drops, not only on the 30 s timer. Keep beat cuts.
Measure what you can on the TV, ask the user to judge by eye, and commit each step separately.
```

Later (bigger): match preset energy to the music's energy; time blends to the beat (tempo detection);
remember per song which preset looked best (needs permission to read what's playing).

## A. Batch projectM custom shapes (engine), after D

Shape-heavy presets crawl on TVs whatever the picture size. Background, measurements and plan:
`docs/performance.md`.

```
Read CLAUDE.md and docs/performance.md. Goal: make shape-heavy MilkDrop presets fast in projectM by batching
custom shape drawing, and prepare an upstream pull request.

1. Search projectM GitHub issues/PRs for earlier work on custom shape or draw-call performance. Report before coding.
2. Build the macOS test harness described in CLAUDE.md ("Testing off the TV") if it doesn't exist: projectM built
   natively, offscreen GL context, renders a fixed preset for N frames with fixed audio, prints CPU ms/frame,
   draw calls and buffer updates, and can save frame PNGs. Keep it in tools/bench/ with a README line.
   Use "cope - ants on acid orchestrate chaos - flx roam i hate myself.milk" and 3 other presets from
   app/src/main/assets/heavy.txt.
3. Calibrate: when the TV is free, run the same presets there (hold each preset at 480p, read the fps logs) and
   record Mac ms against TV fps in docs/performance.md, so later Mac results predict this TV and a weaker one.
4. Write the batching change against upstream master. Try 2-3 approaches in parallel git worktrees if they differ
   meaningfully. Pick by draw-call count, Mac timing (run timings one at a time) and identical frame PNGs.
5. Backport the winner to our v4.1.8 submodule as a patch file in the repo, applied at build time. Build the APK,
   install on the TV, hold the "ants on acid" preset at 480p and compare fps with docs/performance.md.
6. Done when: TV fps for that preset is measured before and after, the patch is in the app, and an upstream PR
   branch and description are drafted. Do not open the PR or push without asking me.
```

## B. Late-90s player theme for the app's UI

```
Read CLAUDE.md. Goal: give the app's own UI (menu, preset name box, hints) a late-90s desktop media-player
feel: boxy bevelled panels, green LCD-style text on black, pixel fonts. Inspired by the era, not a copy.
Rules: no Winamp name, logo, skins or llama, and no MilkDrop branding (trademarks). Fonts must have an open
licence (e.g. OFL pixel fonts), with the licence noted in README.

1. Make 3-4 distinct mockups as HTML pages at 1920x1080 over a screenshot of the visuals (from the TV, or the
   emulator). Build them in parallel with subagents. Show me side by side and let me pick.
2. Build the picked one in Android (MenuPanel, layouts, drawables). Keep the rules learned the hard way:
   fade only, no moving views over the GL view, rows never rebuilt for value changes.
3. Check layout and focus on the Android TV emulator (AVD tv34; create it if missing, see CLAUDE.md).
   Then check it costs no frames on the TV: menu-open fps and dumpsys SurfaceFlinger, against docs/performance.md.
4. Update CLAUDE.md's Art section (it says the menu stays neutral grey) and README. Commit when I approve.
```

## C. Smaller items (pick any when A or B is blocked)

- **Preset browser:** the menu's way of finding presets "can be slightly better" (user feedback, 15-Nov-2026).
  Ask what bothers them before designing.
- **Bundle without heavy presets:** `tools/make-bundle.py` still picks heavy presets, which "Skip heavy presets"
  then hides by default (~17% of the 400). Exclude `heavy.txt` names. Note: this changes the random picks, so
  bump `versionCode`, and favourites from the old bundle may disappear for users without the full pack.
- **Skip heavy on fast devices:** after A, decide whether "Skip heavy presets" should default off on strong
  hardware (CPU and GPU are logged at start as `device ...` and in the projectM line).

## Done
- 15-Nov-2026: audio feed fixed. The capture was normalized to full level every 21 ms, which flattened loudness
  swings (cv 0.18-0.25 against 0.33-0.66 as played). Now as played, plus a slow 4 s gain in native.cpp.
- 15-Nov-2026: smoother switches, shader warmer, blends on remote keys, Skip heavy presets, menu art removed
  (`4bd4e0c`).
