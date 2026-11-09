---
paths: ["composeApp/src/**/*.kt"]
---

# FlowState UI (all platforms)

## Style: Cinema Dark
- Colors: background `#0C0D10`, surface `#1A1C22`, accent amber `#E9A23B`, user score green `#4ADE80`,
  IMDb badge `#F5C518`. Define once in a shared theme in `commonMain`; never hardcode hex in screens.
- Dark only. Posters carry the screen; keep chrome quiet.

## Screens
- Home: one Continue Watching hero with Play Now, then poster rows (Recommended, Similar, Upcoming).
- Detail: two-pane. Left: poster, scores, actions (Play Now, Trailer, Season Trailer, Recap, Rate).
  Right: tabs (Episodes with AI summaries, Cast, Similar).
- Poster card: 2:3 poster, progress bar along the bottom, user score badge (0-100) top-right,
  IMDb badge top-left. Badges appear only when data exists.
- Posters: TMDB image CDN primary, Metahub fallback, loaded with Coil 3 plus disk cache and a placeholder.
- Show "AI-generated" on every summary. Show a spoiler-safe state, never reveal unwatched episodes.

## Android Mobile (Material 3)
- Use Material 3 adaptive layouts (`WindowSizeClass`): bottom navigation on compact, rail on medium+.
- Swipeable poster rows, expandable episode lists, 48dp minimum touch targets.
- Support edge-to-edge and system bars insets.

## Navigation per platform
- Mobile: bottom bar. Windows: left rail. TV: top tabs with side rail. See platform rule files.
