---
paths: ["composeApp/src/desktopMain/**/*.kt"]
---

# Windows Desktop UI Rules (Compose Multiplatform Desktop)

## Fluent Design and scaling
- Follow Fluent Design: subtle elevation, 4-8.dp corner radius, accent color for selection/focus, Segoe UI Variable (fallback to system default) for text.
- Respect Windows DPI scaling (100% to 250%). Use `dp`/`sp` only; never hardcode pixel sizes. Test at 100%, 150%, and 200%.
- Support light/dark via `isSystemInDarkTheme()`; follow the Windows app-mode setting.
- Window: resizable with sensible minimum size (e.g. `DpSize(900.dp, 600.dp)`); persist size/position between launches.

## Multi-pane layouts
- Use `BoxWithConstraints` / window-size classes to switch layouts:
  - Compact (< 700.dp): single pane with navigation.
  - Medium (700-1100.dp): nav rail + list/detail two-pane.
  - Expanded (> 1100.dp): nav rail + list + detail (three-pane).
- Panes are resizable with a draggable splitter (`DesktopMaterialTheme` + `HorizontalSplitPane` from `org.jetbrains.compose.splitpane`); persist divider positions.
- Poster grids use `LazyVerticalGrid(GridCells.Adaptive(minSize = 140.dp))`; do not fix column counts.

## Keyboard navigation
- Every interactive element must be reachable by Tab / Shift+Tab with a visible focus indicator (2.dp accent border).
- Required shortcuts via `Modifier.onPreviewKeyEvent` / window `MenuBar` shortcuts:
  - `Ctrl+F` focus search, `Ctrl+R` refresh/sync, `Ctrl+,` settings, `Alt+Left` back, `Esc` close dialog/pane, `Enter` open selected, `Space` toggle watched.
  - Arrow keys move through grids/lists; `Home`/`End` jump to first/last; `PageUp`/`PageDown` scroll a page.
- Show shortcuts in tooltips (`TooltipArea`) and menu items. Do not capture keys while a text field is focused.
- Use `FocusRequester` to restore focus to the previously selected item after closing dialogs or panes.

## High-density list controls
- Provide a density toggle (Comfortable / Compact); compact rows are 32-40.dp tall, comfortable 48-56.dp.
- Lists use `LazyColumn` with `key = { it.id }`, sticky headers for seasons, and `VerticalScrollbar` with `rememberScrollbarAdapter`.
- Multi-select: Ctrl+Click toggle, Shift+Click range, Ctrl+A select all; bulk actions (mark watched, rate) in a context bar.
- Right-click context menus via `ContextMenuArea`; hover states via `onPointerEvent(Enter/Exit)` with a subtle background tint.
- Columns (title, year, progress, score /100) are sortable by clicking headers.

## Platform behavior
- System tray (when enabled) uses `Tray` + `rememberTrayState`; closing the window hides to tray only if the user setting is on.
- Windows-only APIs (protocol handlers, DPAPI, registry) live in `desktopMain` actuals; keep shared UI pure in `commonMain`.
- Collect StateFlows with `collectAsState()` at the composition root only; hoist state to shared ViewModels.
