# FlowState (`com.fitzy.flowstate`)

Compose Multiplatform tracker: Android, Android TV, Windows. Syncs watch state via Trakt (primary; covers Nuvio) and optionally Stremio/Nuvio directly.
Specs: `SPEC.md`. Progress and next steps: `FEATURE_PROGRESS.md` (read it first after `/clear`).

## Modules (AGP 9: KMP plugin cannot share a module with `com.android.application`)
- `shared/` KMP lib (`com.android.kotlin.multiplatform.library` + `jvm("desktop")`)
  - `commonMain/` UI, domain, data, sync logic, SQLDelight schema, Ktor clients
  - `androidMain/` WorkManager, intents, APK installer, encrypted prefs
  - `desktopMain/` tray, scheduler, registry protocol handler, MSI/EXE updater
- `androidApp/` `com.android.application` (phone + TV manifests, Leanback launcher)
- `desktopApp/` `compose.desktop` entry, `packageMsi`

## Commands
- Android/TV: `./gradlew :androidApp:assembleDebug`
- Windows: `./gradlew :desktopApp:run`, `./gradlew :desktopApp:packageMsi`
- Tests: `./gradlew test`
- Verify (required before finishing): `./gradlew check`

## Architecture rules
- Keep UI pure in `commonMain`; platform work via `expect/actual` interfaces.
- Local SQLDelight DB is the source of truth; sync reconciles after.
- Sync conflict rule: newest `_mtime`/`lastWatched` wins; log every conflict.
- Collect flows in Android UI with `collectAsStateWithLifecycle` / `repeatOnLifecycle`.
- Secrets (authKeys, OAuth tokens, OMDb/TMDB keys): platform encrypted storage only.

## Forbidden anti-patterns
- Never run network or DB sync on the main/UI thread.
- Never hardcode non-adaptive focus highlights that break D-pad navigation.
- Never mix `androidx.compose.material3` Card/Button with `androidx.tv.material3` ones on TV screens.
- Never scrape Letterboxd, IMDb or RT; use OMDb and link-outs only.
- Never fire a deep link from untrusted input without validating scheme, host and ID format (see skill `deeplink-handlers`).
- Never commit keys, `.env`, keystores, or API responses containing tokens.
- Never log authKeys or tokens.
- Never invent undocumented API fields; mark unverified items `// UNVERIFIED` and gate them behind a flag.
- Never skip or disable a failing test to get green.

## Unverified areas (feature-flagged until confirmed)
- Direct Nuvio sync (Supabase URL/key are not public; ToS unchecked): optional, flag `nuvioSync`.
- Trakt endpoint details (percent progress, rate limits): verify against a real account.
- `stremio://` URL shapes: confirm on a device before relying on them.

## Token hygiene
- Keep this file under 200 lines.
- Delegate repo-wide searches and web research to subagents; ask for short reports.
- Update `FEATURE_PROGRESS.md` after each task. Run `/clear` at about 80% context.
- Detail rules load by path from `.claude/rules/`; procedures live in `.claude/skills/`.
