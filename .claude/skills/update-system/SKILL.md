---
name: update-system
description: Use when implementing the GitHub Releases update checker, APK installer, or Windows MSI/EXE updater.
---
# Update procedure

- Check: `GET https://api.github.com/repos/{owner}/{repo}/releases/latest` with `User-Agent: FlowState`, ETag/If-None-Match (unauth limit 60/h). Parse `tag_name` (semver, strip `v`), `body`, assets.
- Pick asset: `.apk` on Android/TV, `.msi` (else `.exe`) on Windows. Verify a `sha256` from the release notes or `.sha256` asset before install.
- Skipped version stored in prefs; startup check respects it, manual check ignores it. Startup check runs once per 24h, off the main thread.
- Download to cache dir with progress `StateFlow<Float>`; resumable not required.
- Android: needs `REQUEST_INSTALL_PACKAGES`; if `canRequestPackageInstalls()` is false, open `ACTION_MANAGE_UNKNOWN_APP_SOURCES`. Expose file through FileProvider; install via `PackageInstaller.Session`. Same signing key required. User confirmation is unavoidable.
- Windows: launch `msiexec /i file.msi` (or the exe) then exit the app. No admin elevation inside the app.
- TV: D-pad friendly dialog, big buttons, default focus on "Update now".
