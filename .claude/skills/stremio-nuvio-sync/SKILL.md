---
name: stremio-nuvio-sync
description: Use when implementing Stremio/Nuvio login, token handling, library pull/push, or sync conflict resolution.
---
# Stremio / Nuvio sync procedure

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

## Nuvio (UNVERIFIED, behind flag `nuvioSync`)
- Spike first: read github.com/tapframe/NuvioMobile and NuvioMedia/NuvioTV for the Supabase URL, tables, auth flow and `nuvio://` scheme. Record findings in `SPEC.md` section 5 before coding.
- Until confirmed, ship the UI as "Experimental" and make the adapter return `Unsupported`.

## Threading
All calls on `Dispatchers.IO`; repository exposes `Flow`; Ktor timeouts 15s; retry with exponential backoff (max 3) on 5xx/IO errors, never on 4xx.
