# FlowState feature progress

Update the Status column when a stage finishes. Stages: **S0** scaffold, **S1** shared domain + data, **S2** UI, **S3** platform actuals, **S4** verified.

## Decisions (locked)
Style Cinema Dark. Home Hero + rows. Detail two-pane. Poster card progress + score + IMDb. Native navigation per platform.
Metadata TMDB + Trakt. Disliked actors soft penalty. Windows in MVP. Updates from GitHub Releases `fitzy1590/flowstate`.
Posters: TMDB primary, Metahub fallback.

## Backlog
| ID | Feature | Stage | Verify by |
|---|---|---|---|
| S0 | Gradle KMP project, targets android + desktop, CI, version catalog | done | `./gradlew check :composeApp:assembleDebug` green (Android + desktop tests, lint). `packageMsi` untested (needs Windows) |
| 1a | Rule-based recommender | todo | unit tests on scoring fixtures |
| 1b | Liked/disliked actors, soft penalty | todo | tests: caps +0.20 / -0.30, title never removed |
| 1c | Similar Shows/Movies tab | todo | detail page shows TMDB similar, de-duplicated |
| 1e | Trakt / MDBList lists as sources | todo | list import test with recorded JSON |
| 1f | Add to watchlist from recommendations | todo | card action writes local library |
| 2a | 0-100 rating modal | todo | persists 0..100, rejects out of range |
| 2b | IMDb / RT badges, Letterboxd link | todo | OMDb parse test, badge hidden when absent |
| 3a | Stremio deep link | todo | URI builder tests; manual launch on device |
| 3b | Nuvio deep link (`nuvio://meta?...`) | todo | URI builder tests; manual launch |
| 3c | Default player + next unwatched + no-app prompt | todo | next-episode logic tests |
| 4a | Stremio sync | todo | conflict-resolution tests; sandbox account run |
| 4b | Nuvio sync (read Nuvio sync code first) | todo | same as 4a |
| 4c | Background workers (WorkManager, desktop scheduler) | todo | worker runs on emulator; scheduler test |
| 4e | Conflict log + force pull/push | todo | log rows match test conflicts |
| 4g | Export/import backup | todo | round-trip test |
| 4h | Encrypted credential storage | todo | secrets never in plain prefs/logs |
| 5a | Launch update check | todo | version-compare tests; skipped version persists |
| 5b | Settings "Check for Updates" + progress | todo | progress UI test |
| 5c | Installer wrappers (.apk / .msi) | todo | checksum verify test; manual install |
| 6a | Android Mobile UI | todo | `assembleDebug`, emulator walkthrough |
| 6b | Windows Desktop UI | todo | `:composeApp:run`, keyboard-only walkthrough |
| 6c | Android TV UI | todo | D-pad walkthrough, focus restore works |
| 6e | New-episode notifications | todo | notification fires on test episode |
| 6f | Upcoming calendar | todo | shows unwatched upcoming episodes |
| 7a | Watch Trailer button (TMDB video, YouTube link) | todo | link builder tests; no-trailer fallback |
| 7b | Season trailer (next season after finished) | todo | visibility logic tests |
| 7c | Season recap (YouTube search) | todo | with key opens top result; without key opens search |
| 7d | AI episode summaries + season catch-up | todo | no summary for unwatched episodes; cache hit test |
| 8a | Periodic "interested?" prompt (Yes / Maybe / Never) | todo | frequency + back-off tests; votes change scores |
| 8b | Discover swipe tab + quick add to library | todo | swipe/keys/D-pad map to votes; undo works; add writes library |
| 8c | AI search (text, structured query -> TMDB) | todo | parsed-query tests for both example prompts; no invented titles |
| 8d | Voice input on Android and TV | todo | permission flow; speech text reaches search; device test |
| 8e | Windows voice (v1 hint, v2 Vosk) | todo | v1: hint shown; v2: offline recognition test |

## Open items
- Verify every **[verify]** item in `SPEC.md` before coding it.
- Read Nuvio sync code (`NuvioMedia/NuvioMobile`) to spec 4b.
- Decide secret handling for the Claude API key (user-supplied for now).

## Environment notes
- Android SDK is not preinstalled in cloud sessions. Install cmdline-tools from `dl.google.com`, then `platforms;android-35` and `build-tools;35.0.0`, and write `sdk.dir` to `local.properties` (gitignored).
- Cloud environment `flowstate` allows `dl.google.com` and `maven.google.com`. Maven Central can answer 429 under parallel load: use `--max-workers=1 --no-parallel` and retry.
- CI uses `android-actions/setup-android`.
