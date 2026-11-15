# Next work

The queue for this repo, top item first (the user's order: low effort, high value first; then the engine). Pick up with `/next-work` (or paste a prompt into a new session).
When an item is done, move it to "Done" with the date and commit. Add new items with a ready-to-paste prompt.
Read `CLAUDE.md` first, especially "Testing off the TV".

## P. Play Store release (free public app, open source; the user's next priority, 15-Nov-2026)

Done: upload-key signing in Gradle (`-PplayUpload`, keys in ~/.gradle/gradle.properties), relearning after
renderer updates, About and licences page (`3f5ec7e`), swear words masked in preset names. Target SDK 34 meets
Play's rule for TV apps (checked 15-Nov-2026; phones would need 35/36, so list it for TV only). Waiting on the user: create the upload key (keytool command in the session notes / below), the
Play developer account, Decided: free public app, stays open source.

```
Read CLAUDE.md and docs/next.md item P. Goal: a Play Store build that passes review for Android TV.
1-3. Done (target SDK, About page, masked names).
Drafts done: `docs/privacy.md` (policy URL is its GitHub page), `docs/store/listing.md`, six 1920x1080
screenshots in `docs/store/screenshots` (taken on the TV; let the user pick). Still needed: feature graphic
1024x500, and screenshots of the new menu after B.
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

## F. Finding presets: numbers, search, featured (with B)

User decisions, 15-Nov-2026:
- **Channel numbers:** every preset gets a fixed number; type it on the remote (the user's TCL remote has number
  keys) to jump there. Numbers never change: number the full pack once in a fixed order (style, sub-style, name),
  shared by the bundle and the download; new presets get new numbers at the end. On-screen number pad as fallback
  for remotes without number keys.
- **Search:** both voice (remote mic, Android SpeechRecognizer) and the on-screen keyboard. Matches names and
  authors; masked words still match.
- **Featured:** a playlist of the best-looking, most interesting presets. All presets are rated 5/5 in the pack, so
  curate: a heuristic shortlist (~200: strongly reactive, smooth on the TV, colourful and moving in rendered frames),
  then the user picks ~50 from a review page of thumbnails.
- **Random across styles:** a mode that changes style on every switch, so variety is guaranteed.
- Also: favourites one press away, and "recently played".

```
Read CLAUDE.md and docs/next.md items B and F. Design these into the menu redesign (B) mockups first; build after
the user picks. Numbers: tools/ script writes assets/numbers.txt (number, file name) for the full pack.
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

- **Room microphone mode:** this TCL TV lists far-field voice features (`com.tcl.software.audio_farvoice`) but no
  `android.hardware.microphone`. Test whether an app can open it (AudioRecord, VOICE_RECOGNITION source) while
  "Hey Google" is idle. If yes: a "Listen to the room" option for music from another speaker. Ask the user first.
- **Upstream 0002:** offer `patches/projectm/0002` (non-additive borders as quads) upstream after #1044 merges.
- **Name box under the menu:** the preset name box shows through behind the menu's bottom rows. Fix in B.
- **Menu cost:** any visible view makes the TV compose 4K on the GPU (60 to ~40 fps with the menu open), whatever
  its transparency. Drawing the menu inside the GL surface would avoid it; consider in the redesign (B).
- **Preset fixes upstream:** Cream of the Crop PR #7 (open) fixes expression parse errors in 67 presets. Check how
  many fail on our TV today (`preset failed` in the log), and pull the fixed files into the full pack/bundle.

- **Bundle without heavy presets:** `tools/make-bundle.py` still picks heavy presets, which "Skip heavy presets"
  then hides by default (~17% of the 400). Exclude `heavy.txt` names. Note: this changes the random picks, so
  bump `versionCode`, and favourites from the old bundle may disappear for users without the full pack.
- **Skip heavy on fast devices:** after A2, decide whether "Skip heavy presets" should default off on strong
  hardware (CPU and GPU are logged at start as `device ...` and in the projectM line).

## Done
- 15-Nov-2026: shapes after the backport: patch 0002 batches non-additive borders (phlegmployant 12 to 28 fps,
  mother-of-whirl 10 to 35); heavy list 1,654 to 6; learned sizes reset on renderer updates; Smooth/Sharp auto
  picture, Smooth default (`9701f84`, `57aac24`). #1044 TV results posted upstream.
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
