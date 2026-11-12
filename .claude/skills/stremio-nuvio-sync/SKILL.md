---
name: stremio-nuvio-sync
description: Use when implementing Trakt, Stremio or Nuvio login, token handling, library pull/push, or sync conflict resolution.
---
# Stremio / Nuvio sync procedure

## Trakt (PRIMARY; carries Nuvio progress; details UNVERIFIED, test with real account)
- Base `https://api.trakt.tv`; headers `trakt-api-version: 2`, `trakt-api-key: <client_id>`, `Authorization: Bearer`. Client id/secret come from user config (never committed).
- Login: device flow `POST /oauth/device/code` -> show code + QR -> poll `POST /oauth/device/token`. Refresh with `refresh_token` before expiry and on 401; store tokens encrypted.
- Each cycle: `GET /sync/last_activities`; only fetch categories whose timestamp changed since last sync.
- Pull: `/sync/history/{movies|episodes}?start_at=`, `/sync/playback` (progress is a PERCENT; store as percent, derive ms from duration when known).
- Push: `POST /sync/history` (watched_at ISO UTC), `POST /sync/playback`-style scrobble where applicable, `POST /sync/ratings` (see SPEC 2). Use Trakt ids or imdb ids; dedupe by imdb id + season/episode.
- Conflict: newest timestamp wins (`watched_at`, `paused_at`, `rated_at`); log conflicts. Respect 429 `Retry-After`; ~1 write/s.
- User must set Nuvio's progress source to Trakt; show a hint in onboarding.

## Stremio (verified against stremio-core)
- Base `https://api.strem.io/api/`, all POST JSON, no headers for auth.
- `login` {email,password,facebook:false} -> `result.authKey`. Store only the authKey (encrypted); never the password. On any auth error response, clear the key and prompt re-login. There is no refresh token.
- Pull: `datastoreGet` {authKey, collection:"libraryItem", ids:[], all:true}.
- Push: `datastorePut` {authKey, collection:"libraryItem", changes:[item]}, batched (<=50), with fresh `_mtime` (ISO-8601 UTC).
- Item fields: `_id,name,type,poster,removed,temp,_ctime,_mtime,state{lastWatched,timeWatched,timeOffset(ms),duration,video_id,flaggedWatched,timesWatched,watched,noNotif}`.
- Errors come back as `{error:{message,code}}`; handle both shapes.

## Conflict resolution
1. Compare `_mtime` (remote) with local `updatedAt`; larger wins per item.
2. Tie: prefer the larger `timeOffset` unless `removed` differs (removal wins only if its mtime is strictly newer).
3. Clamp clock skew: reject remote mtimes more than 5 min in the future.
4. Write a `sync_conflict` row (item, local, remote, winner) for the conflict log UI; manual override re-pushes with a fresh mtime.
5. Never push items the local DB has not changed since last sync (use a `dirty` flag).

## Nuvio (source-verified from tapframe/NuvioMobile + NuvioMedia/NuvioTV; server SQL not public)
- Backend is plain Supabase. Base URL and public anon key are NOT in source (build-time `local.properties`). Make both user-configurable settings (`nuvioBaseUrl`, `nuvioAnonKey`); never hardcode or commit them. Header `apikey: <anon>`; user calls use `Authorization: Bearer <access_token>`.
- Auth: `POST /auth/v1/token?grant_type=password` {email,password}; refresh `?grant_type=refresh_token`; refresh on 401/"JWT expired" then retry once. Store refresh token encrypted. TV QR login exists (RPCs `start_tv_login_session`/`poll_tv_login_session` + exchange) but is optional.
- All data via `POST /rest/v1/rpc/<name>`, per profile `p_profile_id` (default 1). Linked devices read the owner's data (`get_sync_owner`).
- Progress: `sync_pull_watch_progress` {p_profile_id,p_since_last_watched?,p_limit?}; `sync_push_watch_progress` {p_profile_id,p_entries:[{content_id,content_type,video_id,season?,episode?,position(ms),duration(ms),last_watched(epoch ms),progress_key}],p_client_id}; `sync_delete_watch_progress` {p_keys,p_profile_id}. Delta: `sync_get_watch_progress_delta_cursor`, `sync_pull_watch_progress_delta` {p_profile_id,p_since_event_id,p_limit} -> events {event_id,operation upsert|delete,...}.
- Watched items: `sync_push_watched_items`/`sync_pull_watched_items` {p_page,p_page_size}/`sync_delete_watched_items`. Library: `sync_pull_library`/`sync_push_library_items`.
- Semantics: completed = position/duration >= 0.90; skip entries with position<=1 and duration<=1; `progress_key` is a client dedupe key (copy NuvioTV `canonicalizeForRemote`); deletes are events. Server conflict rule is INFERRED last-write-wins on `last_watched`: still apply local latest-wins and verify with a test account.
- Mapping to Stremio: Nuvio position/duration ms == Stremio `timeOffset`/`duration` ms; `video_id` = `tt:S:E` in both; `last_watched` epoch ms <-> Stremio ISO `lastWatched`.
- Nuvio does not sync with Stremio accounts, so FlowState is the bridge (Stremio <-> local DB <-> Nuvio).
- No Nuvio ratings table; keep 0-100 ratings local (+Trakt).
- Legal: nuvioapp.space/legal API-use terms unchecked. Use only with the user's own account, keep it opt-in and labelled experimental.

## Threading
All calls on `Dispatchers.IO`; repository exposes `Flow`; Ktor timeouts 15s; retry with exponential backoff (max 3) on 5xx/IO errors, never on 4xx.
