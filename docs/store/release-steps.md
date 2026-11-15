# Release steps: what you do, what Claude does

Google Play, Android TV only (target SDK 34 meets Play's rule for TV apps; phones would need a newer one).
Checked 15-Nov-2026. Fees and rules change: confirm in Play Console when you get there.

## Your steps (Claude cannot do these)

1. **Create the upload key** on the Mac. You choose the password; keep it in your password manager. Run:
   ```
   ! mkdir -p ~/.config/android-signing && /opt/homebrew/opt/openjdk@17/bin/keytool -genkeypair -v \
       -keystore ~/.config/android-signing/upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```
   Then put the password in `~/.gradle/gradle.properties` (never in the repo):
   ```
   playUploadPassword=<your password>
   ```
   Back up `upload.jks` somewhere safe (not Google Drive's dev folder, not the repo). If lost, Google can reset
   it, but it takes days.
2. **Create a Play developer account** at https://play.google.com/console/signup: personal account, one-time
   fee (US$25 when last checked), identity check with a government ID. Approval can take a few days.
3. **Find 12 testers** (friends or family with an Android TV or Google TV, or any Android device for install).
   New personal accounts must run a closed test with 12+ testers for 14 days before going public.
4. **Pick the screenshots** in `docs/store/screenshots` (Claude retakes them once the new menu is done), and
   approve the feature graphic `docs/store/feature-graphic.png` and the text in `docs/store/listing.md`.
5. **In Play Console** (Claude guides you screen by screen, you click):
   - Create app: name from `listing.md`, free, app (not game).
   - Store listing: paste the text, upload graphics. Category Music & Audio.
   - App content: privacy policy URL (in `listing.md`), Data safety (no data collected or shared), content
     rating questionnaire (Everyone), ads: none, target audience 13+ is simplest.
   - Advanced settings > Form factors: add Android TV, opt in to TV review.
   - Testing > Closed testing: create a track, add the testers' emails, upload the bundle Claude builds.
6. **After 14 days** of closed testing: apply for production in Play Console, then roll out.

## Claude's steps

- Build the bundle: `./gradlew bundleRelease -PplayUpload` (after step 1), check the signature, give you the
  `.aab` path. Asks before anything is uploaded.
- Retake TV screenshots with the new menu; TV banner 1280x720.
- Answer review questions, fix what the TV review flags (focus, banner, Leanback launcher).

## Later: Apple TV (parked)

A tvOS version is a separate app: projectM builds for Apple platforms, but the app shell (remote, menu, audio)
is new code, and Apple TV apps cannot capture other apps' audio, which is how this app works on Android. It
would need a different idea (own music player, or the mic). Apple developer program: US$99 a year.
