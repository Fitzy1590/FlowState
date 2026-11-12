# FEATURE_PROGRESS

Resume here after `/clear`. Read `CLAUDE.md`, then this file, then only the `SPEC.md` section for the current task.
Legend: [ ] todo, [~] in progress, [x] done and `./gradlew check` green.

## Current phase
Phases 1-4 (research, guardrails, spec, checkpoint) complete. Next: T1 scaffold.

## Tasks (in order)
- [ ] T0 Spike: read NuvioMobile/NuvioTV source; record Supabase tables, auth, `nuvio://` in SPEC section 4/3. Verify `stremio://` shapes on device. (Gates 3c, 4c)
- [ ] T1 Scaffold Gradle: `shared` (KMP lib), `androidApp`, `desktopApp`; version catalog; `./gradlew check` passes. Verify: `check` green, `desktopApp:run` opens a window, `androidApp:assembleDebug` builds.
- [ ] T2 Data layer 4g: SQLDelight schema (library, user_rating, actor_pref, sync_conflict, settings). Verify: repository unit tests.
- [ ] T3 Stremio client + sync 4a, 4e. Verify: fake-server tests for conflict rules (tie, skew, removal).
- [ ] T4 Background sync 4b (Android) and 4d (Windows tray). Verify: worker test; manual tray run.
- [ ] T5 Metadata: TMDB client, OMDb badges 2b, Letterboxd link 2c. Verify: parser tests with fixtures.
- [ ] T6 Ratings 2a, 2e, 2f. Verify: mapping tests (100<->10), histogram test.
- [ ] T7 Recommendations 1a-1d, 1f. Verify: scoring fixtures (ordering, boost/penalty caps, hide-disliked).
- [ ] T8 Deep links 3a, 3b, 3d, 3e. Verify: validator tests; adb intent test; Windows registry test.
- [ ] T9 UI mobile 6a + calendar 6f. Verify: screenshot/manual on emulator.
- [ ] T10 UI TV 6b, 6c. Verify: D-pad emulator run, focus restoration after back/refresh, scale >= 1.03x.
- [ ] T11 UI Windows 6d. Verify: resize, 100/150/200% DPI, keyboard shortcuts.
- [ ] T12 Updates 5a-5d. Verify: version-compare tests, sha256 check test, install on device/VM.
- [ ] T13 Trakt 4f (+ 1f signal, 2e sync). Verify: device-flow with mocked server.
- [ ] T14 Nuvio 3c, 4c (only if T0 confirms). Verify: flag on/off behavior.
- [ ] T15 Adversarial audit pass (SPEC section 7 table), fix findings, final `./gradlew check`.

## Verification criteria (global)
`./gradlew check` green; no network/DB work on the main thread; TV focus visible on all focusables; no secrets in repo.

## Notes / decisions
- Not selected: 1e, 2d, 5e, 6e, 7b, 7c. Pin/mute actors still needed for 1b; tray still needed for 4d.
- Nuvio items (3c, 4c) selected without 7c: build behind flags after T0.
- Stop hook runs `./gradlew check` only once `./gradlew` exists (so it does not block before T1).
- RT audience score is not available via OMDb; Tomatometer only.
