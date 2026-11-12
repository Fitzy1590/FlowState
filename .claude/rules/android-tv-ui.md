---
paths: ["shared/src/androidMain/**/*.kt", "androidApp/src/**/*.kt", "composeApp/src/androidMain/**/*.kt", "app/src/main/java/**/*.kt"]
---
# Android TV UI rules (tv-material 1.1.0)

- Use `androidx.tv.material3.*` `Card`, `Button`, `Surface`, `ClickableSurfaceDefaults` on TV screens. Never mix with `androidx.compose.material3` equivalents.
- Focus feedback is mandatory on every focusable: scale >= 1.03x (use `ClickableSurfaceDefaults.scale(focusedScale = 1.06f)`) or border >= 2dp in the accent color. Derive colors from the theme, no hardcoded highlights.
- Wrap every row/grid in `Modifier.focusRestorer { fallbackRef }` (experimental: opt in explicitly). Give items stable `key`s so focus survives recomposition.
- Attach `FocusRequester` only after composition: `LaunchedEffect(Unit)` plus `runCatching`. Lazy lists cannot focus uncomposed items; scroll first.
- Handle Back with `BackHandler`: return focus to the previous row, then exit.
- Safe overscan margins: 48dp horizontal, 27dp vertical. Minimum text 14sp; body 18sp+.
- Login uses device-code flow (QR + code). No on-screen keyboard entry for passwords where avoidable.
- Test on an emulator with D-pad input; touch focus behaves differently.
- Sync and image loading must be off the main thread.
