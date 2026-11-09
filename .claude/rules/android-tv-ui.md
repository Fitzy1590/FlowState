---
paths: ["composeApp/src/androidMain/**/*.kt", "app/src/main/java/**/*.kt"]
---

# Android TV UI Rules (Compose TV 1.0.0)

## Components
- Use `androidx.tv.material3.*` for TV screens: `Card`, `Button`, `Surface`, `Text`, `Icon`. Do NOT use `androidx.compose.material3` equivalents on TV; they lack focus/D-pad behavior.
- Use `androidx.tv.material3.ClickableSurfaceDefaults` / `CardDefaults` for focus scale, border, and glow rather than hand-rolled modifiers where available.
- Lists: standard `LazyRow` / `LazyColumn` (and `LazyVerticalGrid` for the scoreboard grid). Use `key = { item.id }` on every `items` call.
- Wrap only the TV-specific layer in `androidMain`; shared content composables stay in `commonMain` and take focus modifiers as parameters.

## Focus visual feedback (required on every focusable item)
- Focused state MUST show at least one of: scale >= 1.03x, or a border >= 2.dp in the accent color.
- Prefer both: `scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)` and `border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, accent)))`.
- Never rely on color change alone. Must be legible from 10 feet.
- Every focusable surface needs a visible unfocused vs focused difference; check with a D-pad walk-through.

## FocusRequester
- Use `remember { FocusRequester() }` and attach with `Modifier.focusRequester(fr)`.
- Request initial focus once per screen, inside `LaunchedEffect(Unit) { fr.requestFocus() }`, only after the target is composed. Guard against `IllegalStateException` (use `runCatching` or `onGloballyPositioned`).
- One initial-focus target per screen (typically the first content card, not the nav rail).

## Focus restoration
- Apply `Modifier.focusRestorer()` to every `LazyRow` / `LazyColumn` / grid container so returning to a list restores the last focused item.
- Pair with `Modifier.focusRestorer { firstItemRequester }` when a fallback target is needed (the first item gets `focusRequester(firstItemRequester)`).
- Preserve `LazyListState` with `rememberLazyListState()` / `rememberSaveable` across back navigation.
- Use `focusGroup()` on rows so D-pad left/right stays in-row and up/down moves between rows.

## D-pad and layout
- Design for 1920x1080 at 10 feet: minimum body text 14.sp equivalent via TV typography, touch-free hit targets >= 48.dp.
- Keep content inside the overscan-safe area (48.dp horizontal, 27.dp vertical padding).
- Handle `Key.Back` with `BackHandler`; never trap focus (every screen must be exit-able with D-pad and Back).
- No hover or touch-only affordances (swipe, long-press-only actions).

## Lifecycle and state
- Collect flows with `collectAsStateWithLifecycle()` (or `repeatOnLifecycle(STARTED)`).
- Keep composables stateless; hoist state to the ViewModel in `commonMain`.
