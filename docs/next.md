# Next work

The queue for this repo, top item first (the user's order: low effort, high value first; then the engine). Pick up with `/next-work` (or paste a prompt into a new session).
When an item is done, move it to "Done" with the date and commit. Add new items with a ready-to-paste prompt.
Read `CLAUDE.md` first, especially "Testing off the TV".

## P. Play Store release (the user's next priority, 15-Nov-2026)

Done: upload-key signing in Gradle (`-PplayUpload`, keys in ~/.gradle/gradle.properties), relearning after
renderer updates. Waiting on the user: create the upload key (keytool command in the session notes / below), the
Play developer account, and the answer to "free public app, or paid later?" (decides open vs open-core).

```
Read CLAUDE.md and docs/next.md item P. Goal: a Play Store build that passes review for Android TV.
1. Target SDK: check Play's current target API rule and raise targetSdk/compileSdk; test on the emulator (tv34)
   and the TV.
2. About / licences screen in the menu: projectM LGPL-2.1 notice and source link (with patches/projectm), preset
   credits (Cream of the Crop authors), app licence MIT, "AI-generated" logo credit.
3. Preset names: some are crude (e.g. "... i fucking HATE ..."). Clean names for display (a word filter or a
   rename list) so the content rating stays "Everyone"; keep file names.
4. Privacy policy page (GitHub Pages): audio is analysed on the device, never stored or sent. Data safety form.
5. Store assets: 1920x1080 TV screenshots, banner, feature graphic, listing text (no "MilkDrop"/"Winamp").
6. `./gradlew bundleRelease -PplayUpload`, upload to a closed test track (new personal accounts: 12+ testers for
   14 days before production). Ask the user before anything is uploaded.
Key creation (user runs it, chooses the password):
  /opt/homebrew/opt/openjdk@17/bin/keytool -genkeypair -v -keystore ~/.config/android-signing/upload.jks
    -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

## D2. Music feel: tune and next steps (after A, or when A is blocked)

Steps 1-2 of D are done (see Done). TV test 15-Nov-2026, 35 min of Spotify (EDM/pop): all 7 track changes
switched within 0-2 s (`gap:` against `dumpsys media_session`). Pauses inside songs (0.5 s, 1.1 s) also switched,
so the minimum gap went from 0.4 s to 1.2 s and gap switches wait until a preset has played 8 s (not re-tested
yet). Open:
- No drop without a gap fired in 35 min (DROP_RATIO 2x may be too strict; check with tracks that build up).
- The 0.7 s blend on gaps has not been judged by eye yet.
- `gap:` logs the level before the gap against the 12 s level: 0.04-0.62x at song ends (fades). Drops after
  silence follow a loud build, so a value > 1 could tell them apart (cut on drops, blend on songs).

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

## A2. After the shape fix: heavy list and upstream

The backport of upstream #1044 tripled shape-heavy presets on the TV (see docs/performance.md). Open:
- **Heavy list is now too strict:** `heavy.txt` (200+ instances, 1,654 presets) and Auto picture's "too slow" list
  were measured before the fix. Re-measure and raise the threshold or drop the list.
- **Non-additive borders** are still one draw per instance: phlegmployant 12 fps, mother-of-whirl 10 fps (1,024
  instances, border_a 0.006, the one slow preset the user noticed). Idea: emit those borders as 1-pixel quads in
  the same triangle list (exact order, one draw call). Upstream follow-up to #1044 (mentioned in our comment).
- Posted the TV results on #1044 (15-Nov-2026) and offered the 4.1 backport for the `v4.1.x` branch. Watch for a
  reply.

```
Read CLAUDE.md, docs/performance.md and docs/next.md item A2. Goal: Skip heavy presets matches the TV after the
shape fix.
1. With tools/tv-bench.sh, measure ~15 presets from heavy.txt spread over instance counts (200-4096) at 480p.
2. Fit fps against instance count (and borders/additive) and set a new threshold in tools/make-heavy-list.py.
   Regenerate heavy.txt. "Re-test picture sizes" clears the old learned "too slow" list on the TV; tell the user.
3. Commit.
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
- **Skip heavy on fast devices:** after A2, decide whether "Skip heavy presets" should default off on strong
  hardware (CPU and GPU are logged at start as `device ...` and in the projectM line).

## Done
- 15-Nov-2026: batched custom shape draws, backport of upstream #1044 as a build-time patch: ants on acid 9.9 to
  30.9 fps at 480p on the TV (`9fbebd3`). Mac harness skipped: the TV measurement settled it.
- 15-Nov-2026: song-aware switching: a gap in the music (new song or a drop after silence) switches on the first
  beat; a drop without a gap cuts (`a933637`).
- 15-Nov-2026: reactivity scores (`tools/make-reactivity-list.py`): 442 deaf presets skipped by default, top
  quarter played twice as often (`82bb9f1`).
- 15-Nov-2026: audio feed fixed. The capture was normalized to full level every 21 ms, which flattened loudness
  swings (cv 0.18-0.25 against 0.33-0.66 as played). Now as played, plus a slow 4 s gain in native.cpp.
- 15-Nov-2026: smoother switches, shader warmer, blends on remote keys, Skip heavy presets, menu art removed
  (`4bd4e0c`).
