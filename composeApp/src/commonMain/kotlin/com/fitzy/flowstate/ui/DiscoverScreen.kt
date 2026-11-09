package com.fitzy.flowstate.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.domain.Vote
import com.fitzy.flowstate.presenter.DiscoverPresenter
import com.fitzy.flowstate.presenter.DiscoverState

@Composable
fun DiscoverRoute(presenter: DiscoverPresenter) {
    val state by presenter.state.collectAsState()
    DiscoverScreen(state, presenter::vote, presenter::addToLibrary, presenter::undo)
}

private const val SWIPE_THRESHOLD_PX = 220f

/**
 * Swipe right = Yes, left = Never, up = Maybe. Keys: Right/Left/Up, A = add to library, Ctrl+Z = undo.
 * The same keys work with a TV D-pad.
 */
@Composable
fun DiscoverScreen(state: DiscoverState, onVote: (Vote) -> Unit, onAdd: () -> Unit, onUndo: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.DirectionRight -> { onVote(Vote.YES); true }
                    e.key == Key.DirectionLeft -> { onVote(Vote.NEVER); true }
                    e.key == Key.DirectionUp -> { onVote(Vote.MAYBE); true }
                    e.key == Key.A -> { onAdd(); true }
                    e.key == Key.Z && e.isCtrlPressed -> { onUndo(); true }
                    else -> false
                }
            }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val c = state.current
        if (c == null) {
            Spacer(Modifier.weight(1f))
            Text(if (state.loading) "Loading" else "You have seen every suggestion for now.")
            Spacer(Modifier.weight(1f))
        } else {
            var dx by remember(c.id) { mutableStateOf(0f) }
            var dy by remember(c.id) { mutableStateOf(0f) }
            Text("${state.remaining} left", color = MaterialTheme.colorScheme.onSurface)
            PosterCard(
                c,
                Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 320.dp)
                    .graphicsLayer { translationX = dx; translationY = dy; rotationZ = dx / 40f }
                    .pointerInput(c.id) {
                        detectDragGestures(
                            onDragEnd = {
                                when {
                                    dx > SWIPE_THRESHOLD_PX -> onVote(Vote.YES)
                                    dx < -SWIPE_THRESHOLD_PX -> onVote(Vote.NEVER)
                                    dy < -SWIPE_THRESHOLD_PX -> onVote(Vote.MAYBE)
                                }
                                dx = 0f; dy = 0f
                            },
                            onDragCancel = { dx = 0f; dy = 0f },
                        ) { change, drag -> change.consume(); dx += drag.x; dy += drag.y }
                    },
            )
            Text("${c.name}${c.year?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onVote(Vote.NEVER) }) { Text("Never") }
                OutlinedButton(onClick = { onVote(Vote.MAYBE) }) { Text("Maybe") }
                Button(onClick = { onVote(Vote.YES) }) { Text("Yes") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAdd) { Text("Add to library") }
                TextButton(onClick = onUndo, enabled = state.canUndo) { Text("Undo") }
            }
        }
    }
}
