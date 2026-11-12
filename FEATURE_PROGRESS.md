# FEATURE_PROGRESS

Resume here after `/clear`. Read `CLAUDE.md`, then this file, then only the `SPEC.md` section for the current task.
Legend: [ ] todo, [~] in progress, [x] done and `./gradlew check` green.

## Current phase
Phases 1-4 (research, guardrails, spec, checkpoint) complete. T1 done. Next: T2 (SQLDelight data layer).

## Tasks (in order)
- [x] T0 Nuvio spike done (findings in SPEC 3/4 + skill `stremio-nuvio-sync`). Still open: `stremio://` device test; user to supply Nuvio base URL + anon key (not in source); confirm server conflict rule with a test account; check nuvioapp.space/legal.
- Decision: Nuvio (main player) is tracked via Trakt (Nuvio uses Trakt as its progress source). Order: T1, T2, T3t (Trakt), then T4+. Direct Nuvio sync (T3n) and Stremio (T3) are optional later add-ons.
- [x] T1 (check green, debug APK builds, desktop compiles; `desktopApp:run` not yet seen on a display) Scaffold Gradle: `shared` (KMP lib), `androidApp`, `desktopApp`; version catalog; `./gradlew check` passes. Verify: `check` green, `desktopApp:run` opens a window, `androidApp:assembleDebug` builds.
- [ ] T2 Data layer 4g: SQLDelight schema (library, user_rating, actor_pref, sync_conflict, settings). Verify: repository unit tests.
- [ ] T3t Trakt client + 2-way sync 4f, 4e (PRIMARY, covers Nuvio): device-code login, token refresh, `/sync/last_activities` delta check, history + playback pull/push, dedupe by ids, conflict log. Verify: MockEngine tests (refresh retry, delta skip, 429 Retry-After backoff, conflict rules); manual run with the user's Trakt account.
- [ ] T3n (later, optional) Direct Nuvio client + sync 4c, 4e (GoTrue login+refresh, progress/watched RPCs, delta cursor, progress_key, profile select). Verify: MockEngine tests for token refresh retry, push/pull mapping, 0.90 completion, delete events; manual run against user's account.
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
- [ ] T13 Trakt extras: 1f recommendation signal, 2e rating sync (client from T3t). Verify: device-flow with mocked server.
- [ ] T14 Nuvio 3c deep link; 4c direct sync only if the user wants it (flag `nuvioSync`). Verify: flag on/off behavior.
- [ ] T15 Adversarial audit pass (SPEC section 7 table), fix findings, final `./gradlew check`.

## Verification criteria (global)
`./gradlew check` green; no network/DB work on the main thread; TV focus visible on all focusables; no secrets in repo.

## Notes / decisions
- Not selected: 1e, 2d, 5e, 6e, 7b, 7c. Pin/mute actors still needed for 1b; tray still needed for 4d.
- Nuvio items: 4c direct sync is optional/later (flag `nuvioSync`); Nuvio progress arrives via Trakt. 3c Nuvio deep link stays (Android/TV).
- User must set Nuvio's watch-progress source to Trakt (Nuvio settings) for FlowState to see it.
- Stop hook runs `./gradlew check` only once `./gradlew` exists (so it does not block before T1).
- RT audience score is not available via OMDb; Tomatometer only.
- Toolchain (T1): Gradle 9.9.1 wrapper, AGP 9.5.1, Kotlin 2.4.21, Compose MP 1.12.2, compileSdk 37 (needed by Compose 1.12 deps), min 26, target 36. Android SDK lives at /opt/android-sdk (`local.properties` sdk.dir, gitignored); a fresh container must reinstall it (cmdline-tools, platforms;android-37.0, build-tools;37.0.0). Maven Central can return 429; retry with `--no-parallel`.
