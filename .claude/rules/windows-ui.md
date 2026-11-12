---
paths: ["shared/src/desktopMain/**/*.kt", "desktopApp/src/**/*.kt", "composeApp/src/desktopMain/**/*.kt"]
---
# Windows desktop UI rules

- Layout: resizable multi-pane (nav rail, grid, detail). Collapse to two panes below 900dp, one below 600dp.
- Fluent feel: 8dp grid, 4dp corner radius, subtle elevation, system accent color where readable. High-density lists (row height 40-48dp).
- Keyboard: Ctrl+F search, Ctrl+R sync now, arrows/Enter in grids, Esc closes dialogs. Visible focus ring on every control.
- DPI: rely on Skia automatic scaling. Supply tray and window icons at 16/32/48/256 px; the default tray icon scales badly.
- Tray: `application { }` with `exitProcessOnExit = false`; closing the window hides to tray. Copy `ApplicationScope.Tray` if click handlers are needed.
- Multi-window: separate `Window {}` composables; share state through the shared ViewModel layer, never through globals.
- Single instance: lock file or local socket; forward `stremio`/`flowstate` URI args to the running instance.
- Run sync on `Dispatchers.IO`; never block the AWT/Compose thread.
- Protocol handlers and installers: use HKCU registry writes, never require admin for normal operation.
