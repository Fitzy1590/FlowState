# Device test plan

Nothing below has been run on a real device. Cloud sessions can build and unit test, but cannot run an Android phone, an Android TV or Windows.
Mark each line pass or fail with the date, then record failures in `FEATURE_PROGRESS.md`.

## What is already confirmed (no device)
- `./gradlew check :composeApp:assembleDebug` passes (unit tests, Android lint).
- Debug APK manifest: launchable on phones and Android TV (leanback launcher), TV banner present, touchscreen and leanback marked not required, min SDK 24, target SDK 35.
- Windows desktop build starts and shows Home under a virtual display (Linux). Not seen: Discover, Detail, Settings, any click.

## Build and install
- Android / TV: `./gradlew :composeApp:assembleDebug`, then `adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk`.
- Windows: `./gradlew :composeApp:run`. Installer: `./gradlew :composeApp:packageMsi` (Windows only, not yet tried).

## Android phone
- [ ] App starts; Home shows hero, recommended row, interest card (first launch only, then not again for 24h).
- [ ] Tap a poster: detail opens; system Back returns to Home.
- [ ] Discover: drag right = Yes, left = Never, up = Maybe; buttons work; Add to library; Undo.
- [ ] Settings: save a TMDB token, relaunch, token still "Saved" (Keystore encryption works), real posters load.
- [ ] Rate a title 0-100; value shows in green and survives restart.
- [ ] Play in Stremio / Open in Nuvio with each app installed, and with neither installed (message appears, no crash).
- [ ] Watch trailer opens YouTube (app or browser).
- [ ] Show: Episodes tab, tick episodes, library progress changes, season trailer and recap buttons follow the rule (finish S1 then S2 trailer and S1 recap).
- [ ] With a Claude key: episode Summary and Catch me up work, and only for watched episodes.
- [ ] Stremio: Connect, Sync now; a movie watched in Stremio appears here; a movie finished here appears in Stremio.

## Android TV (D-pad only, no touch)
Known gaps before testing, since the UI is shared Material 3 and not yet the TV layer:
- Cards use plain Material components, not `androidx.tv.material3`, so there is no focus scale or accent border yet (rule: scale >= 1.03x or border >= 2dp).
- No `focusRestorer()` on rows, no initial focus target on Home, no top-tab / side-rail TV navigation.
- Text fields (Settings) need an on-screen keyboard or a paired phone.
Checks:
- [ ] App appears in the TV launcher with its banner.
- [ ] Can every screen be reached and left with D-pad and Back? Is there ever no visible focus?
- [ ] Discover: D-pad Left / Right / Up vote, OK on Add to library.
- [ ] Remote microphone voice search (not built yet).

## Windows
- [ ] Window opens at 1200x800, will not shrink below 900x600.
- [ ] Settings: save a key, restart, still "Saved" (DPAPI works). Data in `%APPDATA%\FlowState`.
- [ ] Keyboard: Left / Right / Up vote on Discover, `A` adds, `Ctrl+Z` undoes, `Esc` leaves the detail page.
- [ ] `stremio://` links open Stremio Desktop; `nuvio://` opens Nuvio if installed.
- [ ] `./gradlew :composeApp:packageMsi` produces an installer that installs and starts.
- [ ] Layout at 100%, 150% and 200% display scaling.

## Still unverified against live services
- TMDB response shapes (saved cloud credential returned 401), season videos and find-by-IMDb endpoints.
- Metahub poster URL, YouTube Data API search, Claude Messages API call, Stremio login and datastore calls.
- `stremio:///detail/...` link format.
