---
name: deeplink-handlers
description: Use when building Play Now deep links, Android intents, Windows protocol handler registration, or the web fallback.
---
# Deep link procedure

## URL shapes (UNVERIFIED: confirm on device first)
- Series episode: `stremio:///detail/series/{imdbId}/{imdbId}:{season}:{episode}`
- Movie: `stremio:///detail/movie/{imdbId}/{imdbId}`
- Web fallback: `https://web.stremio.com/#/detail/{type}/{imdbId}/{videoId}`
- Nuvio: `nuvio://` unknown. Hide the option until the scheme is confirmed (flag `nuvioLink`).

## Safety
- Build URIs only from validated parts: `imdbId` matches `^tt\d{1,10}$`, season/episode are ints 0..9999, type in {movie,series}. Reject otherwise.
- Never accept a URI string from an intent, clipboard or network response and pass it through; construct it.
- Inbound `flowstate://` links: whitelist host/path and validate params the same way.

## Android / TV
- `PackageManager.queryIntentActivities` (declare `<queries>` for the scheme) to check the player is installed; else show web fallback.
- `Intent(ACTION_VIEW, uri).addFlags(FLAG_ACTIVITY_NEW_TASK)`; wrap `startActivity` in try/catch `ActivityNotFoundException`.
- On TV, fall back to a dialog if no handler exists.

## Windows
- Check HKCU/HKCR `Software\Classes\stremio\shell\open\command` for installation.
- Launch with `Desktop.getDesktop().browse(uri)` on `Dispatchers.IO`; fall back to web on failure.
- Register FlowState's own `flowstate://` in HKCU on first run (no admin). Single-instance forwards args.

## Test
`adb shell am start -a android.intent.action.VIEW -d "stremio:///detail/movie/tt0133093/tt0133093"`
