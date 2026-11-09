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
| 1a | Rule-based recommender | S1 done (scorer + 13 tests); UI and data wiring todo | unit tests on scoring fixtures |
| 1b | Liked/disliked actors, soft penalty | S1 done (caps and never-removed tested); actor list UI todo | tests: caps +0.20 / -0.30, title never removed |
| 1c | Similar Shows/Movies tab | todo | detail page shows TMDB similar, de-duplicated |
| 1e | Trakt / MDBList lists as sources | todo | list import test with recorded JSON |
| 1f | Add to watchlist from recommendations | S1 done (`quickAdd`); UI todo | card action writes local library |
| 2a | 0-100 rating modal | S1 done (stored 0..100, range checked); modal built on the detail page (slider + number box), not yet seen on screen | persists 0..100, rejects out of range |
| 2b | IMDb / RT badges, Letterboxd link | todo | OMDb parse test, badge hidden when absent |
| 3a | Stremio deep link | S1+S2 done (link builder, "Play in Stremio" button, launchers); real device launch todo | URI builder tests; manual launch on device |
| 3b | Nuvio deep link (`nuvio://meta?...`) | S1+S2 done (matches Nuvio source; "Open in Nuvio" button); device launch todo | URI builder tests; manual launch |
| 3c | Default player + next unwatched + no-app prompt | todo | next-episode logic tests |
| 4a | Stremio sync | S1+S2 done (movies two-way, shows pull-only; login, Sync now, sync at launch); never run against a real Stremio account | conflict-resolution tests; sandbox account run |
| 4b | Nuvio sync | BLOCKED: Nuvio uses a private Supabase backend (see SPEC 4). Proposed: Trakt bridge | same as 4a |
| 4c | Background workers (WorkManager, desktop scheduler) | todo | worker runs on emulator; scheduler test |
| 4e | Conflict log + force pull/push | partly: sync log stored and last 5 shown in Settings; force pull/push not built | log rows match test conflicts |
| 4g | Export/import backup | todo | round-trip test |
| 4h | Encrypted credential storage | S1 done (store, Android Keystore AES-GCM, Windows DPAPI, Settings screen); on-device and on-Windows check todo | secrets never in plain prefs/logs |
| 5a | Launch update check | todo | version-compare tests; skipped version persists |
| 5b | Settings "Check for Updates" + progress | todo | progress UI test |
| 5c | Installer wrappers (.apk / .msi) | todo | checksum verify test; manual install |
| 6a | Android Mobile UI | todo | `assembleDebug`, emulator walkthrough |
| 6b | Windows Desktop UI | todo | `:composeApp:run`, keyboard-only walkthrough |
| 6c | Android TV UI | todo | D-pad walkthrough, focus restore works |
| 6e | New-episode notifications | todo | notification fires on test episode |
| 6f | Upcoming calendar | todo | shows unwatched upcoming episodes |
| 7a | Watch Trailer button (TMDB video, YouTube link) | S1+S2 done (uses TMDB trailer key, falls back to YouTube search); autoplay depends on YouTube app/browser | link builder tests; no-trailer fallback |
| 7b | Season trailer (next season after finished) | S1+S2 done (`SeasonPlan` rule, TMDB season videos, button on Episodes tab); live endpoint unverified | visibility logic tests |
| 7c | Season recap (YouTube search) | S1+S2 done (`YoutubeClient`, falls back to search page without key); live API unverified | with key opens top result; without key opens search |
| 7d | AI episode summaries + season catch-up | S1+S2 done (Claude Haiku client, cache per episode, watched-only rule); live Claude call never run | no summary for unwatched episodes; cache hit test |
| 8a | Periodic "interested?" prompt (Yes / Maybe / Never) | S1 done (`InterestPrompter` rules + vote store); card UI first pass on Home (sample data), device check todo | frequency + back-off tests; votes change scores |
| 8b | Discover swipe tab + quick add to library | S1 done (votes, `quickAdd`); swipe UI first pass (touch drag, keys), device check todo | swipe/keys/D-pad map to votes; undo works; add writes library |
| 8c | AI search (text, structured query -> TMDB) | todo | parsed-query tests for both example prompts; no invented titles |
| 8d | Voice input on Android and TV | todo | permission flow; speech text reaches search; device test |
| 8e | Windows voice (v1 hint, v2 Vosk) | todo | v1: hint shown; v2: offline recognition test |

## Detail page (first pass)
Tap a poster on Home to open it. Two panes when wide (poster, actions left; overview right), one column when narrow; Esc or Back returns. Actions: Play in Stremio, Open in Nuvio, Watch trailer, Add to library, Rate 0-100. Episodes tab built (shows only): season chips, watched checkboxes that drive library progress, per-episode "Summary", "Catch me up", season trailer and recap buttons; unwatched episodes show no description. Not built yet: Cast / Similar tabs, IMDb and Rotten Tomatoes badges, season trailer and recap, AI episode summaries, default-player setting and next-episode logic (3c). Screen not yet seen rendered (no way to click in the headless run).

## Sync (first pass)
`SyncEngine` (latest activity wins, equal = no change, no deletes), `StremioApi`, `StremioSync`, `SyncPresenter`, Stremio section in Settings. Not built: background workers (4c: WorkManager / desktop scheduler; sync currently runs at launch and on Sync now), decoding the show watched bitfield, Trakt bridge.

## Open items
- Verify every **[verify]** item in `SPEC.md` before coding it.
- Read Nuvio sync code (`NuvioMedia/NuvioMobile`) to spec 4b.
- Decide secret handling for the Claude API key (user-supplied for now).

## Environment notes
- Android SDK is not preinstalled in cloud sessions. Install cmdline-tools from `dl.google.com`, then `platforms;android-35` and `build-tools;35.0.0`, and write `sdk.dir` to `local.properties` (gitignored).
- Cloud environment `flowstate` allows `dl.google.com` and `maven.google.com`. Maven Central can answer 429 under parallel load: use `--max-workers=1 --no-parallel` and retry.
- CI uses `android-actions/setup-android`.
- S1 done: domain, recommender, TMDB client, SQLDelight database, repository, `InterestPrompter`, `HomePresenter`, `DiscoverPresenter`. 52 tests pass (`./gradlew check`).
- S2 first pass: Home (hero, interest card, recommended row) and Discover (swipe card) run on `SampleCatalog` (dev data, not TMDB). Desktop Home screen checked in a screenshot under a virtual display. Not yet checked: Discover screen, Android, TV, touch swipe.
- Known gaps: navigation is one shared bottom bar (spec says native per platform); flows use `collectAsState` (CLAUDE.md asks for lifecycle-aware collection on Android); DB calls run on the main thread; poster images load through Coil 3 (TMDB, then Metahub, placeholder if both fail; disk cache 200 MB desktop / 100 MB Android) but have not been seen loading: the sample list has no poster data, and `image.tmdb.org` is not reachable from the cloud environment.
- TMDB response shapes and the Metahub URL are untested against live services (no TMDB key here, `images.metahub.space` is not in the cloud allowlist). Check both before relying on them.
- `TmdbCatalog` built (recommendations for liked titles, then popular movies/shows; details for the top 30; 6h cache; falls back to the last good pool). Tested on mock responses only. A saved TMDB token on the cloud environment still returned 401 "Invalid API key", so real TMDB response shapes remain UNVERIFIED. Re-save the v4 read access token (starts `eyJ`) and re-run the probe.
- Keys (TMDB, Claude, YouTube) are entered in the Settings tab and stored encrypted: Android Keystore on Android, DPAPI on Windows. On Linux dev machines they are memory-only. `SecretAwareCatalog` uses TMDB when a token is saved and `SampleCatalog` otherwise. The Keystore and DPAPI code is untested on a real device or Windows.
- Next: verify TMDB shapes (the saved cloud credential still gives 401 after re-saving) and see real posters load, per-platform navigation, then ratings modal (2a) and detail page.
