# FlowState Technical Spec

Status markers: **[verified]** checked against real source or docs this session, **[verify]** from memory, confirm before coding.

## 1. Recommendation engine (1a, 1b, 1c, 1e, 1f)
Sources: watch history + 0-100 scores (local DB), TMDB (`/similar`, `/recommendations`, `/credits`), Trakt (related, lists), MDBList lists (1e).

Score per candidate, all inputs normalized to 0..1:

| Signal | Weight | Definition |
|---|---|---|
| Genre affinity | 0.25 | Mean over the title's genres of the user's genre score. Genre score = smoothed mean of `(score/100) * completion` for watched titles, Bayesian prior 0.5 with k=5 |
| TMDB/Trakt similarity | 0.20 | Rank-decayed vote from titles the user rated >= 70: `1/(1+rank)` summed, capped at 1 |
| Rating pattern | 0.15 | Closeness of the title's TMDB vote average to the user's mean score for similar genres |
| Completion rate | 0.15 | Share of started titles in matching genres finished. Titles abandoned under 25% after 30 days count as negative |
| Explicit votes (8a/8b) | 0.15 | Genre/keyword/cast affinity learned from Yes / Maybe / Never votes, see section 8 |
| Recency | 0.10 | Newer releases get a small boost, linear over 10 years |

Actor adjustment (soft penalty, per your vote), applied after the weighted sum, over the top 5 billed cast:
- Billing factor by position: 1.0, 0.8, 0.6, 0.4, 0.3.
- Liked actor: `+0.08 * factor`, total capped at `+0.20`.
- Disliked actor: `-0.10 * factor`, total capped at `-0.30`. Never removes a title outright.
- Final = clamp(base + adjustment, 0, 1). Titles already watched are excluded.
- Actor preferences are explicit user lists, editable in Settings and from a cast member's screen.

1f: each recommendation card has an "Add to watchlist" action (writes to local library, synced by section 4).
Similar tab (1c): TMDB `/similar` then `/recommendations`, de-duplicated, same scoring applied.

## 2. Ratings (2a, 2b)
- **User score 2a:** integer 0..100 in `UserRating(itemId, score, ratedAt)`. Post-watch modal. Local only for now (2c not selected).
- **Badges 2b:** OMDb `?i={imdbId}&apikey=` returns `imdbRating` and Rotten Tomatoes % in `Ratings[]` **[verify]**. OMDb gives the Tomatometer only, not the Audience score, so the Audience score is not shown.
- **Letterboxd:** no public API. Link out with `https://letterboxd.com/imdb/{imdbId}/` **[verify]**. No score badge.
- Cache ratings 7 days per title. Show a badge only when data exists.

## 3. Deep links (3a, 3b, 3c)
- **Stremio 3a:** `stremio:///detail/{movie|series}/{imdbId}` and, for episodes, `.../{imdbId}:{season}:{episode}` as the video id **[verify]**. Android: `Intent.ACTION_VIEW` with the URI. Windows: `Desktop.browse` / shell open, relying on the protocol handler Stremio registers.
- **Nuvio 3b [verified in NuvioMobile source]:** `nuvio://meta?type={type}&id={id}` (URL-encoded) opens the title's page. Nuvio's `stremio://` handler is for addon install only, so never send Stremio detail links to Nuvio. No episode-level link was found, so Nuvio opens the title page.
- **3c:** Settings picks the default player (Stremio or Nuvio). Play Now resolves the next unwatched episode and opens it. If the app is not installed (`ActivityNotFoundException` or no handler), show an install prompt and offer the other player.

## 4. Sync and background (4a, 4b, 4c, 4e, 4g, 4h)
- **Model:** each library item has `lastActivityAt`; progress is `(position, duration, watched, season, episode)`. Remote and local are compared per item; the later `lastActivityAt` wins. Equal timestamps keep local. Never delete remote items when a fetch fails or returns empty.
- **Stremio 4a [verified against `stremio-core` source, not run live]:** `POST https://api.strem.io/api/login` with `{"type":"Login","email","password","facebook":false}` returns `{"result":{"authKey",...}}` or `{"error":{message,code}}`. `POST /api/datastoreGet` with `{authKey, collection:"libraryItem", ids:[], all:true}` returns `{"result":[items]}`; `POST /api/datastorePut` with `{authKey, collection:"libraryItem", changes:[items]}` writes. Item ids are IMDb ids (`tt...`). Progress lives in `state`: `lastWatched`, `timeOffset`, `duration` (ms), `flaggedWatched`, `video_id`; episode-level watched is a separate `watched` bitfield. Implemented: movies sync both ways (latest `lastWatched` wins); shows are pull-only because the episode bitfield is not decoded, and pushed items keep all unknown fields untouched. Only the session key is stored.
- **Nuvio 4b [verified in `NuvioMedia/NuvioMobile` source, never run against a server]:** Nuvio syncs through a Supabase backend. The official hosted service's address and key are built into Nuvio's own apps and are not published, and Nuvio's apps themselves refuse it on the "custom server" path, so FlowState does not connect to it. **Self-hosted Nuvio servers are supported**: the user types the server address, FlowState reads `GET {server}/.well-known/nuvio` (`version`=1, `service`="nuvio", `self_hosted`=true, `backend_url`, `publishable_key`, `capabilities.email_password_auth`), signs in with Supabase email/password (`POST {backend}/auth/v1/token?grant_type=password`, refresh with `grant_type=refresh_token`, header `apikey`), lists profiles with `rpc/sync_pull_profiles` (`profile_index`, `name`), then syncs with `rpc/sync_pull_watched_items` / `sync_push_watched_items` (movies and single episodes, `content_id` = IMDb id, `content_type` "movie" or "series", `season`, `episode`, `watched_at` ms) and `rpc/sync_pull_watch_progress` / `sync_push_watch_progress` (movie progress). Rules: watched marks are a union (anything watched on either side ends up on both, nothing deleted, so unwatching must be done on both); movie progress is latest-activity-wins; 90% progress counts as watched; show progress inside an episode is not synced. Plain http is refused for public hosts. Official-hosted users can use Trakt later as a bridge (not built).
- **Schedule 4c:** Android and TV: `WorkManager` periodic, 15 min minimum, network-connected constraint, exponential backoff, plus a sync on app open. Windows: coroutine scheduler in-process (every 15 min while running); tray worker is not in scope.
- **Conflict log 4e:** table `SyncEvent(at, item, winner, localTs, remoteTs)`, viewable in Settings, with Force Pull and Force Push per account.
- **Backup 4g:** export/import JSON of library, scores, actor lists, settings (no secrets).
- **Credentials 4h:** Android Keystore-backed encrypted prefs; Windows DPAPI via JNA. Interface `SecretStore` in `commonMain`, `actual` per platform.

## 5. Updates (5a, 5b, 5c)
- Source: `GET https://api.github.com/repos/fitzy1590/flowstate/releases/latest`. Parse `tag_name` as semver, compare to `BuildConfig.VERSION_NAME`, pick asset by suffix (`.apk` Android/TV, `.msi` Windows).
- **5a:** automatic update (on by default, switch in Settings): at launch, at most every 6 hours, a newer version is downloaded, checksum-verified and installed with no question asked, unless that version was skipped. Android uses `PackageInstaller` with "user action not required" (Android 12+, honoured when this app installed the current version; otherwise the system shows its own confirmation, handled by `InstallStatusReceiver`). Windows runs `msiexec /i /passive` (administrator permission is still requested) and closes the app first. With auto off: dialog "Update Now" / "Skip Version" as before. Original text: silent check at launch, at most once per 6 hours. If newer and not the skipped version: dialog "Update Now" / "Skip Version" (persist skipped tag).
- **5b:** Settings > "Check for Updates" always checks and shows result, with a download progress bar.
- **5c:** Android: download to cache, verify SHA-256 against the release's checksum file, install via `PackageInstaller` (needs `REQUEST_INSTALL_PACKAGES`; signing key must match). Windows: download `.msi`, verify checksum, launch `msiexec /i` and exit.
- Unauthenticated GitHub API allows 60 requests/hour per IP; the 6-hour throttle stays well under it.

## 6. Posters (TMDB primary, Metahub fallback)
- TMDB: `https://image.tmdb.org/t/p/{w185|w342|w500|original}{poster_path}`. Show TMDB attribution in Settings > About.
- Fallback: Metahub poster by IMDb id **[verify URL pattern]** when TMDB has no poster.
- Coil 3 multiplatform with memory and disk cache; solid placeholder while loading.

## 7. Trailers, recaps, AI summaries (new)
Prior art: a GitHub search found no well-starred project that generates per-episode catch-up summaries. Closest is SkipDB (38 stars, crowdsourced intro/recap skip timestamps), which is a different problem. These features are built from scratch.

**7a Trailer button.** TMDB `/movie/{id}/videos` or `/tv/{id}/videos`; pick `site=YouTube`, `type=Trailer`, prefer `official=true`, newest. Open `https://www.youtube.com/watch?v={key}` (YouTube app on Android/TV if installed, default browser on Windows). This replaces searching YouTube, so no YouTube API key is needed. Whether playback autoplays is up to the YouTube app or browser; a watch link normally starts playing after the user's tap. If TMDB has no trailer, fall back to a YouTube search link (no autoplay).

**7b Season trailer.** TMDB `/tv/{id}/season/{n}/videos` **[verify]**. Show "Season N+1 trailer" when the user has finished season N and a trailer exists for N+1; also show the current season's trailer when the user has not started it. If TMDB has none, hide the button.

**7c Season recap.** TMDB has no recap videos, so this needs YouTube search. Show "Season N recap" once the user has finished season N (or is on the last episode). Resolution order:
1. YouTube Data API v3 `search.list` with `q="{show} season {n} recap"`, `type=video`, `maxResults=1` (needs a user-supplied API key, 100 quota units per search of 10,000/day), then open the first result's watch URL. Cache the video id per `(show, season)`.
2. No key: open `https://www.youtube.com/results?search_query=...` (user picks; no autoplay).
Recap videos are third-party and may contain spoilers for later seasons; label them "Third-party video".

**7d AI episode summaries.** Goal: remember what happened.
- Input: TMDB episode `name` and `overview` (and Trakt overview when TMDB's is empty). No subtitles or video are read.
- Model: Claude Haiku (`claude-haiku-5-5`) via the Anthropic Messages API, user-supplied key in `SecretStore`. Output 2-3 sentences per episode.
- Prompt rules: use only the provided text, do not add facts, do not mention anything beyond the given episode, plain present tense.
- Spoiler safety: generate and show only for episodes up to the user's current progress. Unwatched episodes show the normal TMDB overview collapsed behind "Show spoilers".
- "Catch me up" per season: concatenates the watched episodes' summaries into a 4-6 sentence season recap.
- Cache in `EpisodeSummary(showId, season, episode, lang, promptVersion, text, createdAt)`; regenerate only if the prompt version changes. Without a key or offline, show the TMDB overview. Label every summary "AI-generated".
- Limit: if the source overview is empty, show "No description available" rather than generating one.

## 8. Interest votes, Discover swipe tab, AI search (new)

**Vote model.** `InterestVote(titleId, type, vote, at, source)` with `vote` in `YES | MAYBE | NEVER` and `source` in `PROMPT | SWIPE | SEARCH`. One current vote per title; a new vote replaces the old one.
- YES: raises affinity of the title's genres, keywords and top-billed cast (+1.0 weight unit each).
- MAYBE: +0.3 weight units, and the title may be shown again after 30 days.
- NEVER: title is never recommended again, and its genres/keywords get -0.2 weight units. It never changes the actor lists on its own; the app may suggest "Also dislike {actor}?" and the user confirms.
- Affinity per feature = smoothed mean of vote weights (same Bayesian prior as genres). The "Explicit votes" signal in section 1 is `clamp(0.5 + 0.5 * mean affinity of the candidate's genres, keywords and top-5 cast, 0, 1)`, so no votes gives a neutral 0.5. Unknown features count as 0. Implemented in `reco/Recommender.kt`.

**8a Interest prompt.** A card on Home ("Would this interest you?" with Yes / Maybe / Never), not a modal.
- Frequency: at most one per app launch and one per 24 hours; never while a sync or playback is running; skipped if the user dismissed three in a row (back off for 7 days).
- Candidate choice: 70% top recommender picks not yet voted on, 30% exploration picks where the profile is least certain (genres or cast with few votes). Titles already in the library or voted NEVER are excluded.
- Card shows poster, year, genres, TMDB overview. Hit targets work with touch, mouse, and D-pad.

**8b Discover tab.** New top-level tab with a card stack.
- Swipe right = Yes, swipe left = Never, swipe up or "?" button = Maybe. A visible "Add to library" button on every card adds the title to the library as Planned (also counts as Yes if the user has not voted). Undo reverts the last vote.
- Input per platform: Mobile: touch swipe plus the buttons. Windows: buttons and keys (`Left`=Never, `Right`=Yes, `Up`=Maybe, `A`=add, `Ctrl+Z`=undo). TV: D-pad Left/Right/Up and a focused Add button.
- Deck source: recommender candidates (section 1), prefetching the next 10 posters; filter chips for Movies / Shows / genre.
- Every vote feeds section 1 immediately and is stored locally; sync of votes is not in scope.

**8c AI search (text).** Search box on its own tab and on Home. Examples: "show me old popular war movies", "I liked Band of Brothers and Saving Private Ryan, show me similar shows and movies".
1. One Claude Haiku (`claude-haiku-5-5`) call turns the text into a structured query via a tool schema: `{mediaTypes, genres, yearFrom, yearTo, popularity, moods, keywords, seedTitles[], exclude[]}`. The model never returns titles to show; this prevents invented titles.
2. Resolve against TMDB:
   - Filter queries use `/discover/{movie|tv}` with `with_genres`, `primary_release_date` range, `sort_by=vote_count.desc` and `vote_count.gte` for "popular" (e.g. "old" -> release year before 2000, adjustable). Genre ids come from TMDB's genre list (War = 10752 for movies **[verify]**).
   - Seed queries resolve each seed with `/search`, then union `/recommendations` and `/similar` for each. A title appearing for more than one seed ranks higher. Seeds, watched titles and NEVER titles are removed.
3. Rank with the section 1 scorer, then show a poster list. The interpreted query is shown as editable chips ("War", "Movies", "before 2000", "similar to Band of Brothers") so the user can correct a misread.
4. Selecting a result opens the detail page with Play Now, Add to library, and Trailer.
- Mood queries ("something cozy", "I want to laugh") map to TMDB genres and keywords through the same structured query.
- Without a Claude key the search falls back to a local parser (genre and year words, plus title search) and says it is limited.
- Privacy: only the typed or spoken text is sent to Claude, not the library or ratings.

**8d Voice input, Android and Android TV.** `expect fun rememberVoiceInput()`; Android `actual` uses `SpeechRecognizer` (or `RecognizerIntent`) with `RECORD_AUDIO` permission requested on first mic tap; result text goes into the search box and runs 8c. Show a listening state and a retry. On TV the remote microphone works where the device's speech service supports it **[verify on a real TV]**; text entry stays available.

**8e Voice input, Windows.** The JVM has no simple built-in speech API. Options: (a) v1: text box and a hint to use Windows dictation (`Win+H`); (b) v2: offline recognition with Vosk (about 50 MB model download, no cloud); (c) cloud speech-to-text, which needs another key. Recommend (a) now, (b) later. Claude's API does not take audio, so it cannot do this step.
