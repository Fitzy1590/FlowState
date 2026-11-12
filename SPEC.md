# FlowState Technical Spec

Status: draft v1. Items marked UNVERIFIED depend on APIs not confirmed by source; they ship behind flags.

## 0. Approved backlog (vote recorded)

### 1 Recommendations
- [x] 1a Taste-vector recommendations
- [x] 1b Liked/Disliked actor weighting
- [x] 1c "Because you like..." explanations
- [x] 1d Similar Shows/Movies tab
- [ ] 1e Actor pin/mute + learned affinity (not selected; pin/mute is still needed for 1b, see 1.2)
- [x] 1f Trakt as extra signal
### 2 Ratings
- [x] 2a 0-100 rating modal
- [x] 2b IMDb + Rotten Tomatoes badges
- [x] 2c Letterboxd link-out
- [ ] 2d Rating display toggle
- [x] 2e Sync rating to Trakt
- [x] 2f Stats dashboard
### 3 Play Now
- [x] 3a Stremio link (Android/TV)
- [x] 3b Stremio link (Windows)
- [x] 3c Nuvio link (UNVERIFIED, flag `nuvioLink`)
- [x] 3d Web fallback
- [x] 3e Default player setting
### 4 Sync
- [x] 4a Stremio 2-way sync
- [x] 4b Android background sync
- [x] 4c Nuvio sync (UNVERIFIED, flag `nuvioSync`; spike first)
- [x] 4d Windows tray sync
- [x] 4e Conflict log + manual override
- [x] 4f Optional Trakt sync
- [x] 4g Offline-first DB (SQLDelight)
### 5 Updates
- [x] 5a Startup update prompt
- [x] 5b Settings "Check for Updates"
- [x] 5c Android APK installer
- [x] 5d Windows installer
- [ ] 5e Release channel toggle
### 6 Layout
- [x] 6a Android mobile
- [x] 6b Android TV D-pad grid (tv-material 1.1.0)
- [x] 6c TV device-code login
- [x] 6d Windows multi-pane
- [ ] 6e Windows tray mini-tracker (not selected; 4d tray still exists)
- [x] 6f Next-episode calendar
### 7 Architecture
- [x] 7a 3-module layout (`shared`, `androidApp`, `desktopApp`)
- [ ] 7b single module (rejected)
- [ ] 7c skip Nuvio in v1 (NOT selected: Nuvio is in scope, gated by flags and a research spike)

## 1. Recommendation engine
Formula and weights: skill `reco-scoring`. Summary:
`Score = 100*clamp(.30 Genre + .25 Actor + .25 Similar + .10 Trakt + .10 Completion - Penalty)`.
1.1 Data: TMDB credits (top 8 billed), `/similar`, `/recommendations`; Trakt `/recommendations` if connected (else weight redistributed).
1.2 Actor weighting: per-actor affinity from ratings (shrunk mean, k=3). User can mark actors Liked (+0.25) or Disliked (-0.30 each, cap -0.6) from the cast list; setting "Hide disliked" hard-filters. Liked/disliked lists are stored locally, never synced.
1.3 Explanations: top 2 contributing reasons per result.
1.4 Detail page tabs: Episodes | Similar | Cast. Similar tab = TMDB `/similar` re-ranked by Score.

## 2. Ratings
- Local rating: integer 0-100, table `user_rating(imdbId, score, ratedAt)`. Modal shown after completing an episode/movie (dismissible).
- IMDb + RT: OMDb `GET http://www.omdbapi.com/?apikey=KEY&i=tt...` -> `imdbRating`, `Ratings[]` (Source "Rotten Tomatoes"). Audience score is not in OMDb: show Tomatometer only; hide Audience badge. User supplies the key (free 1,000/day); cache 7 days.
- Letterboxd: link-out only: `https://letterboxd.com/imdb/{ttid}`. No scores, no scraping.
- Trakt rating sync (2e): `score10 = round(score/10)` clamped 1-10, `POST /sync/ratings`. Import: `score = r*10`. Local value wins and is never overwritten by a round-trip unless Trakt rating differs from `round(local/10)`.
- Stats (2f): rating histogram (10 bins), total hours (sum `timeWatched`), top actors by affinity, titles count.

## 3. Deep linking
Procedure and safety rules: skill `deeplink-handlers`. Default player stored in prefs (`stremio|nuvio|ask`). Web fallback when handler is missing. Nuvio scheme unknown: hidden until the spike confirms it.

## 4. Sync
- Local DB is the source of truth; `dirty` flag per row.
- Stremio: see skill `stremio-nuvio-sync` (login -> authKey, datastoreGet/Put, `_mtime` latest-wins).
- Schedule: Android WorkManager periodic 15 min (constraints: network; Wi-Fi-only default on), plus on app foreground. Windows: coroutine scheduler every 15 min in tray-resident process (`exitProcessOnExit=false`); "Sync now" in tray menu. TV uses the Android worker.
- Conflict log table `sync_conflict`; UI list with Keep local / Keep remote (re-push with fresh mtime).
- Trakt (optional): OAuth device flow (`/oauth/device/code`, `/oauth/device/token`), `/sync/watched`, `/sync/playback`; rate 1 POST/s, ~1000 GET/5 min (verify); Trakt timestamps follow the same latest-wins rule.
- Nuvio: adapter interface `AccountSync`; Nuvio impl returns `Unsupported` until the spike (`FEATURE_PROGRESS.md` T0) records endpoints.

## 5. Updates
Procedure: skill `update-system`. GitHub `releases/latest`, ETag, skip-version, sha256 check, `.apk`/`.msi`. Startup check once per 24h; Settings button forces a check with progress bar.

## 6. UI per platform
- Mobile: Material 3 adaptive; swipeable poster rows; expandable season/episode list; calendar (6f) with airing countdown.
- TV: rules in `.claude/rules/android-tv-ui.md`; device-code login (6c) with QR + code.
- Windows: rules in `.claude/rules/windows-ui.md`; multi-pane, shortcuts, tray.

## 7. Adversarial audit (resolve before shipping each area)
| Risk | Mitigation |
|---|---|
| TV focus lost after list refresh or sync | Stable keys, `focusRestorer`, restore on resume; test with emulator D-pad script |
| TV focus request before composition crashes | `LaunchedEffect` + `runCatching`; scroll-to-index first |
| Windows DPI / multi-monitor mixed scaling | Test 100/150/200%; vector icons; tray icon multi-size |
| Windows multi-window shared state | Single ViewModel layer, no globals |
| Clock skew breaks latest-wins | Clamp future mtimes (+5 min); use server mtime when present |
| Sync loop (push triggers pull triggers push) | `dirty` flag; only push local changes; ignore echoes by comparing mtime |
| Deleted vs. updated race | Removal wins only if strictly newer |
| Deep link injection via crafted IDs | Regex-validated, constructed URIs only |
| Exported intent-filter abuse (`flowstate://`) | Validate params, no side effects without user action |
| Token leakage | Encrypted storage, no logging, deny-listed secrets in `.claude/settings.json` |
| APK update tampering | HTTPS, sha256 check, same-signature enforcement by Android |
| Android 15+ background limits | WorkManager only; expedited work not assumed |
| AGP 9 plugin incompatibility | 3-module layout (7a) |
| Unverified Nuvio/`stremio://` shapes | Feature flags + device tests, spike task T0 |

## 8. Dependencies (versions to be pinned at scaffold time)
Kotlin, Compose Multiplatform, AGP 9.x with `com.android.kotlin.multiplatform.library`, `androidx.tv:tv-material` 1.1.0, Ktor client, kotlinx-serialization, SQLDelight, Coil 3, WorkManager (Android only), Koin or manual DI.
