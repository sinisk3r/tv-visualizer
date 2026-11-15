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

## S. Feels smooth everywhere (the user's ask, 15-Nov-2026)

Ideas in order of felt gain per effort:
1. ~~Even frame pacing~~ done 15-Nov-2026: a preset settled at 31-46 fps draws on every second vsync (an even
   30); Smooth steps down below 55 (was 45) so pacing only applies where 480p cannot reach 60. The TV ignores
   eglSwapInterval(2), so the activity renders on demand from Choreographer. Judge by eye: is the 46 fps edge
   right? (green machine 52 fps stays unpaced; interference 39 fps is paced).
2. ~~CPU performance hints~~ tried 15-Nov-2026, dropped: PerformanceHintManager session (16.7 ms target) made
   no difference on the bench set (each preset within 0.3 fps of hints off). The TV is GPU-bound at 480p.
3. Right size from the first frame: predict a new preset's size from its features (unbatched shapes, wave samples,
   shader length, blur) instead of a guess corrected after ~3 s.
4. True dynamic resolution: render projectM into an internal picture of any size and upscale it ourselves, in
   small steps every ~0.5 s, instead of resizing the surface (1.5 s settle, 4 steps). Resizing resets some trails:
   change on switches or in small steps.
5. Per-pixel mesh size by preset cost (32x24 now). Measured 15-Nov-2026, 480p, 16x12 vs 32x24: phlegmployant
   27.4 to 30.1 fps (slow frames 27 to 0), ants 40.5 to 41.8, interference 39.1 to 39.3. Only CPU-heavy presets
   gain (~10%). If built: coarser mesh only for presets still under 31 fps at 480p, after the user judges
   whether 16x12 warps look blocky.
6. Menu drawn inside the GL surface (no 4K composition while open), with B.

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

## B. Menu redesign: the transport bar (with F)

User pick, 15-Nov-2026: **mockup A, transport bar** (`design/mockups/a-transport-bar.html`, published at
https://claude.ai/artifact/Dm2ckc7ckzTYwxEVmEvpd7): it shows where to look and stays out of the way most. The
browser (find) panel needs work. User decisions:
- **Search:** a keyboard of **four rows** of letters (shorter, so results are closer), the **mic button on the
  right** of the keyboard. Results sit to the right: Right moves into them, Up/Down scroll, Left goes back to
  the keyboard. Voice: press mic, speak, results appear, press Right.
- **Number keypad** on screen, for search by channel number and for remotes without number keys.
- **Shuffle, two modes:** album shuffle (within one set: a style, Featured, favourites, a smart list) and full
  shuffle (everything available).
- **Smart favourites:** favourite a whole style or single presets; smart playlists from viewing data (most
  watched in the last month / three months), so newer presets get the same attention as the classics. Needs a
  local play log (preset, seconds watched, date), kept on the TV only (privacy policy: no data leaves it).
- **Numbers (15-Nov-2026):** 0 then OK opens Favourites; 01, 02, ... 012 play single favourites. Playlists: 00
  opens the list, 001 Featured, 002 Most watched, 003 New to you, 004 Recently played (proposed instead of the
  user's 1xx, which clashes with presets 100-199; in mockup A2, awaiting the user). Presets keep 1-9,791.
- **Picture:** only Smooth or Sharp in the main settings; fixed 480p-1080p only under Advanced (a fixed size
  forces a worse picture on most presets).
- **Watching, no menu (15-Nov-2026):** Left/Right previous/next preset, Up/Down previous/next style (as today),
  0-9 channel, OK opens the bar. A small "now playing" tag top right for 4 s on every change, automatic ones
  too (today: only on key presses). Setting "Show what's playing", on. Costs frames while shown (4K compose).
- **Settings (15-Nov-2026):** a small card from the bottom, focus on Playback: time per preset, Picture as
  Smooth or Sharp with the live fps and size. Two rows open the rest: **Live and advanced** (big beats, song
  change, skip heavy, skip deaf, unhide all, get all, fixed picture size, re-test sizes, show frame rate) and
  **About and licences** (its own viewer).
- **Preview pictures:** lists show a picture of the focused preset beside the rows. Source: a frame the TV saves
  when a preset plays (glReadPixels at ~10 s, small JPEG in app storage), so favourites and recent always have
  one; shipped thumbnails for the rest (bundle: ~400 x ~10 KB; full pack: with the download), rendered off the
  TV by the Mac harness (to build; also scores Featured candidates).
- No Winamp name, logo or skins and no MilkDrop branding. The menu stays grey.

```
Read CLAUDE.md and docs/next.md items B and F. Rework mockup A's find panel with the decisions above (four-row
keyboard, mic on the right, results to the right, number keypad, shuffle modes, smart lists, settings page),
publish it to the same artifact (A2: design/mockups/a2-transport-bar.html, published 15-Nov-2026) and revise until the user approves. Build nothing until the user approves the mockup. Then build it in Android (MenuPanel, layouts,
drawables): fade only, no moving views over the GL view, rows never rebuilt for value changes, views GONE when
hidden. Check layout and focus on the emulator (AVD tv34), then menu-open fps and dumpsys SurfaceFlinger on the
TV. Fix the name box showing under the menu. Update README and CLAUDE.md. Commit when the user approves.
```

## T. Tests and code quality (the user's ask, 15-Nov-2026)

Started 15-Nov-2026: JVM tests for Settings and preset names (Robolectric), Python checks for the asset lists
(numbers never move, bundle has no deaf or heavy presets), CI on every push. Next, in order:
1. Pull logic out of MainActivity/VizRenderer into plain classes with tests: ~~channel-number entry~~
   (`ChannelEntry`, done), ~~Auto picture and pacing decisions~~ (`FrameRatePolicy`, done, checked on the TV),
   next the play order (weighted shuffle, filters, held start preset, `play()` slotting).
2. Song and drop detection (`detectSong` in native.cpp) as a host C++ test fed with recorded RMS curves.
3. UI tests for the new menu (B) on the emulator (AVD tv34, Espresso): focus order, keys, no row rebuilds.
4. TV checks stay manual but scripted: `tools/tv-bench.sh` before/after any renderer change.

```
Read CLAUDE.md "Tests". Do step 1 one class at a time: move the logic without changing behaviour, write the
tests first against today's behaviour, then refactor. Run both suites; CI must stay green.
```

## M. Music controls and now playing from the visualizer (idea, 15-Nov-2026)

The user's question: control the music app (next, pause) from our remote, and show song, artist and album art.
- **Controls without any permission:** `AudioManager.dispatchMediaKeyEvent` (next, previous, play/pause) goes to
  whichever app holds the active media session (Spotify, YouTube Music...).
- **Song, artist, album art:** `MediaSessionManager.getActiveSessions` gives metadata and artwork, but needs
  notification access, which the user grants once in TV Settings (this TV has the screen:
  `NotificationAccessActivity`). Also unlocks exact song changes for D2.
- **YouTube / YouTube Music cast from a phone:** the casting app plays inside the TV's YouTube app, which stops
  when our app comes to the front, so nothing reaches us. Spotify Connect keeps playing in the background,
  which is why it works. Ways around it: Bluetooth from the phone to the TV as a speaker (if the TV accepts
  A2DP sink; to test), the room mic (item C, no mic feature listed on this TV), or the music app's own
  background play (YouTube Music Premium on some TVs). To test on the TV with the user.

```
Read CLAUDE.md. Mock up first (mockup A2): a music strip in the bar (art, title, artist, previous/play/next) and
in the now-playing tag. After the user agrees: media keys first (no permission), then optional notification
access with a one-time explainer. Test with Spotify and YouTube Music. Update the privacy policy (metadata stays
on the TV).
```

## H. HDR picture (idea, 15-Nov-2026)

The TV reports HDR10, HLG, Dolby Vision and HDR10+ (`dumpsys display`). Presets are SDR (8-bit colour), so
real HDR means expanding them: render as now, then a final pass maps SDR to HDR10 (PQ, BT.2020) with an
inverse tone curve that lifts only the brightest parts (glows, flashes, beat peaks) while mid-tones stay put.
Needs an EGL surface with a 10-bit or FP16 format and `EGL_EXT_gl_colorspace_bt2020_pq`.

Checked 15-Nov-2026: **unlikely on this TV.** EGL has `EGL_KHR_gl_colorspace`, `EGL_EXT_pixel_format_float` and
the HDR metadata extensions, but no `bt2020_pq` or scRGB colour space, and SurfaceFlinger says `Device supports
wide color: 0`. HDR here is probably the video plane only. One cheap test left: an RGBA1010102 surface with
`ANativeWindow_setBuffersDataSpace(ADATASPACE_BT2020_PQ)` and SMPTE2086 metadata, then see whether the TV
switches to HDR mode. Parked until the menu (B) ships.

```
Read CLAUDE.md. Check the TV's EGL extensions for bt2020_pq / scrgb and 10-bit configs. If present, build a
test: final blit with an inverse tone curve into a PQ surface, behind a hidden switch. Compare side by side on
the TV with the user (bright presets: fountains, plasma). Cost: one extra full-screen pass at 4K output.
Report fps against docs/performance.md. Ask the user before making it a setting.
```

## U. Your own presets (developers and fans, idea, 15-Nov-2026)

Let people try their own .milk presets on the TV before sharing them. No open upload library exists for
MilkDrop presets; the shared homes are the projectM preset repos on GitHub (pull requests). Plan:
- **Add from your phone:** the TV app runs a small web page on the local network (shown as a QR code). Drop
  .milk files and textures on it; they land in a "My presets" style, ready to play. Nothing leaves the house.
- **Live edit:** re-load a preset when its file changes, so authors edit on a computer and watch the TV.
- **Share:** a link explaining how to send good ones upstream (projectM preset repo pull request).

```
Read CLAUDE.md. Build "Add from your phone": a minimal HTTP server in the app (only while the page is open in the
menu), a QR code with the TV's local address, upload of .milk and texture files into files/presets/My presets.
Never log or commit the TV's address. Ask the user before adding the INTERNET permission (it changes the Data
safety form and privacy policy).
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

- **Skip heavy on fast devices:** after A2, decide whether "Skip heavy presets" should default off on strong
  hardware (CPU and GPU are logged at start as `device ...` and in the projectM line).

## Done
- 15-Nov-2026: bundle swaps its 22 deaf picks (hidden by default) for reacting ones from the same style; the
  other 378 stay, so favourites survive. None of the 6 heavy presets were in it. versionCode 4.
- 15-Nov-2026: even 30 fps pacing for presets that settle at 31-46 fps; Smooth steps down below 55 fps.
  CPU performance hints tried, no gain (`3290c83`).
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
