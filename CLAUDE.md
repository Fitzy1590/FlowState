# FlowState (`com.fitzy.flowstate`)

Compose Multiplatform app (Android, Android TV, Windows) that tracks TV/movie progress,
two-way syncs with Stremio + Nuvio, recommends titles (actor-weighted), and deep-links into players.
Full design: `SPEC.md`. Backlog and status: `FEATURE_PROGRESS.md`. Read those on demand, not every turn.

## Build and test
- Android / TV debug: `./gradlew :composeApp:assembleDebug`
- Unit tests: `./gradlew test`
- Windows run: `./gradlew :composeApp:run`; installer: `./gradlew :composeApp:packageMsi`
- Full verification: `./gradlew check`

## Structure
- `composeApp/src/commonMain/` shared UI, ViewModels, domain, data, API clients
- `composeApp/src/androidMain/` Android + TV bindings (WorkManager, Intents, Keystore, PackageInstaller)
- `composeApp/src/desktopMain/` Windows launcher (tray, protocol handler, DPAPI, MSI/EXE update)
- Platform code goes behind `expect`/`actual`. Shared code never imports Android or AWT APIs.

## Rules
- Keep UI composables pure and stateless in `commonMain`; hoist state to ViewModels.
- Collect flows with `collectAsStateWithLifecycle()` / `repeatOnLifecycle(STARTED)` on Android.
- Secrets (Stremio/Nuvio/Trakt tokens, Claude and TMDB keys) go in encrypted platform storage only.
  Never log them, never commit them, never read `.env`, `secrets/`, or keystores.
- Sync conflicts: latest `lastActivityAt` wins. Never delete remote data on a failed sync.
- Spoilers: episode summaries and recaps only cover episodes the user has watched.
- UI style is "Cinema Dark" (tokens in `.claude/rules/ui-design.md`). Platform UI rules live in
  `.claude/rules/` and load by path.
- Do not invent API shapes. Verify against the real endpoint or docs, and note it in `SPEC.md`.

## Token hygiene
- Keep this file under 200 lines.
- Use subagents for repo-wide searches and research; keep outputs short.
- Update `FEATURE_PROGRESS.md` when a stage finishes, then `/clear` at about 80% context.
