# Next work

The queue for this repo, top item first (the user's order: low effort, high value first; then the engine). Pick up with `/next-work` (or paste a prompt into a new session).
When an item is done, move it to "Done" with the date and commit. Add new items with a ready-to-paste prompt.
Read `CLAUDE.md` first, especially "Testing off the TV".

## D2. Music feel: tune and next steps (after A, or when A is blocked)

Steps 1-2 of D are done (see Done). What the first TV test (15-Nov-2026, Spotify) left open:
- A 1 s pause in the "Animals" intro counted as a gap and switched the preset mid-song. Harmless (it was a pause in
  the music) but a longer minimum gap (0.4 s now; real song gaps were 1.6-3.2 s) would skip it.
- The 0.7 s blend on gaps has not been judged by eye yet.
- `gap:` logs the level before the gap against the 12 s level: 0.45x at a song's fade out. If drops after silence
  show > 1 (a loud build), this could tell songs from drops (cut on drops, blend on songs).

```
Read CLAUDE.md and docs/next.md item D2. Goal: tune song-aware switching on the TV with real music.
1. Watch `adb logcat -s Visualizer | grep -E "gap:|drop:"` next to the track changes from
   `adb shell dumpsys media_session` (Spotify). Ask the user to play a few albums and EDM tracks with drops.
2. Count true and false switches. Tune SONG_GAP_SECONDS, DROP_RATIO and the gap blend in native.cpp/VizRenderer.
3. Ask the user to judge by eye. Commit.
```

Parked: a visual delay setting (-100..+300 ms) for setups with audio lag (Bluetooth); not needed on the
user's soundbar.

Later (bigger): match preset energy to the music's energy (reactivity.txt has the scores); time blends to the
beat (tempo detection); use the media session for exact song changes and per-song memory (needs notification
access).

## A. Batch projectM custom shapes (engine)

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
- 15-Nov-2026: song-aware switching: a gap in the music (new song or a drop after silence) switches on the first
  beat; a drop without a gap cuts (`a933637`).
- 15-Nov-2026: reactivity scores (`tools/make-reactivity-list.py`): 442 deaf presets skipped by default, top
  quarter played twice as often (`82bb9f1`).
- 15-Nov-2026: audio feed fixed. The capture was normalized to full level every 21 ms, which flattened loudness
  swings (cv 0.18-0.25 against 0.33-0.66 as played). Now as played, plus a slow 4 s gain in native.cpp.
- 15-Nov-2026: smoother switches, shader warmer, blends on remote keys, Skip heavy presets, menu art removed
  (`4bd4e0c`).
