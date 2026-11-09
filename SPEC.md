# FlowState Technical Spec

Status markers: **[verified]** checked against real source or docs this session, **[verify]** from memory, confirm before coding.

## 1. Recommendation engine (1a, 1b, 1c, 1e, 1f)
Sources: watch history + 0-100 scores (local DB), TMDB (`/similar`, `/recommendations`, `/credits`), Trakt (related, lists), MDBList lists (1e).

Score per candidate, all inputs normalized to 0..1:

| Signal | Weight | Definition |
|---|---|---|
| Genre affinity | 0.30 | Mean over the title's genres of the user's genre score. Genre score = smoothed mean of `(score/100) * completion` for watched titles, Bayesian prior 0.5 with k=5 |
| TMDB/Trakt similarity | 0.25 | Rank-decayed vote from titles the user rated >= 70: `1/(1+rank)` summed, capped at 1 |
| Rating pattern | 0.20 | Closeness of the title's TMDB vote average to the user's mean score for similar genres |
| Completion rate | 0.15 | Share of started titles in matching genres finished. Titles abandoned under 25% after 30 days count as negative |
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
- **Stremio 4a:** `api.strem.io` datastore (`datastoreGet` / `datastorePut`, `libraryItem` collection, `authKey` from login) **[verify]**.
- **Nuvio 4b:** backend and auth flow not yet confirmed. Phase 5 task: read the sync code in `NuvioMedia/NuvioMobile` and record endpoints here before coding.
- **Schedule 4c:** Android and TV: `WorkManager` periodic, 15 min minimum, network-connected constraint, exponential backoff, plus a sync on app open. Windows: coroutine scheduler in-process (every 15 min while running); tray worker is not in scope.
- **Conflict log 4e:** table `SyncEvent(at, item, winner, localTs, remoteTs)`, viewable in Settings, with Force Pull and Force Push per account.
- **Backup 4g:** export/import JSON of library, scores, actor lists, settings (no secrets).
- **Credentials 4h:** Android Keystore-backed encrypted prefs; Windows DPAPI via JNA. Interface `SecretStore` in `commonMain`, `actual` per platform.

## 5. Updates (5a, 5b, 5c)
- Source: `GET https://api.github.com/repos/fitzy1590/flowstate/releases/latest`. Parse `tag_name` as semver, compare to `BuildConfig.VERSION_NAME`, pick asset by suffix (`.apk` Android/TV, `.msi` Windows).
- **5a:** silent check at launch, at most once per 6 hours. If newer and not the skipped version: dialog "Update Now" / "Skip Version" (persist skipped tag).
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
