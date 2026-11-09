package com.fitzy.flowstate.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.links.parseScore
import com.fitzy.flowstate.presenter.DetailPresenter
import com.fitzy.flowstate.presenter.EpisodesPresenter
import com.fitzy.flowstate.presenter.DetailState
import com.fitzy.flowstate.presenter.Player
import com.fitzy.flowstate.theme.FlowColors

@Composable
fun DetailRoute(presenter: DetailPresenter, episodes: EpisodesPresenter?, onBack: () -> Unit) {
    val state by presenter.state.collectAsState()
    DetailScreen(
        state, onBack,
        onRate = presenter::rate, onAdd = presenter::addToLibrary,
        onPlay = presenter::play, onTrailer = presenter::watchTrailer, onDismissMessage = presenter::dismissMessage,
        episodes = episodes?.let { ep -> { EpisodesRoute(ep) } },
    )
}

/** Two panes when wide (poster and actions left, overview right); one column when narrow. Esc goes back. */
@Composable
fun DetailScreen(
    state: DetailState,
    onBack: () -> Unit,
    onRate: (Int) -> Boolean,
    onAdd: () -> Unit,
    onPlay: (Player) -> Unit,
    onTrailer: () -> Unit,
    onDismissMessage: () -> Unit,
    episodes: (@Composable () -> Unit)? = null,
) {
    var rating by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onBack(); true } else false
        }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val actions: @Composable () -> Unit = { Actions(state, { rating = true }, onAdd, onPlay, onTrailer) }
            if (maxWidth >= 700.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PosterCard(state.candidate, Modifier.fillMaxWidth())
                        actions()
                    }
                    RightPane(state, episodes, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PosterCard(state.candidate, Modifier.width(200.dp))
                    Overview(state, Modifier)
                    actions()
                    episodes?.invoke()
                }
            }
        }
    }

    if (rating) {
        RatingDialog(state.userScore ?: 70, onSave = { if (onRate(it)) rating = false }, onDismiss = { rating = false })
    }
    state.message?.let {
        AlertDialog(onDismissRequest = onDismissMessage, confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } }, text = { Text(it) })
    }
}

/** Overview and, for shows, an Episodes tab. */
@Composable
private fun RightPane(state: DetailState, episodes: (@Composable () -> Unit)?, modifier: Modifier) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier) {
        if (episodes != null) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Overview") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Episodes") })
            }
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (tab == 1 && episodes != null) episodes() else Overview(state, Modifier)
        }
    }
}

@Composable
private fun Overview(state: DetailState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.candidate.name, style = MaterialTheme.typography.headlineMedium)
        Text(
            listOfNotNull(state.candidate.year?.toString(), "TMDB ${"%.1f".format(state.candidate.voteAverage)}".takeIf { state.candidate.voteAverage > 0 }).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            state.userScore?.let { "Your score: $it / 100" } ?: "Not rated yet",
            color = if (state.userScore != null) FlowColors.UserScore else MaterialTheme.colorScheme.onSurface,
        )
        when {
            state.loading -> Text("Loading")
            state.overview.isNotBlank() -> Text(state.overview)
            else -> Text("No description available.")
        }
    }
}

@Composable
private fun Actions(state: DetailState, onRate: () -> Unit, onAdd: () -> Unit, onPlay: (Player) -> Unit, onTrailer: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onPlay(Player.STREMIO) }, modifier = Modifier.fillMaxWidth()) { Text("Play in Stremio") }
        OutlinedButton(onClick = { onPlay(Player.NUVIO) }, modifier = Modifier.fillMaxWidth()) { Text("Open in Nuvio") }
        OutlinedButton(onClick = onTrailer, modifier = Modifier.fillMaxWidth()) { Text("Watch trailer") }
        FilledTonalButton(onClick = onAdd, enabled = !state.inLibrary, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.inLibrary) "In your library" else "Add to library")
        }
        OutlinedButton(onClick = onRate, modifier = Modifier.fillMaxWidth()) { Text(if (state.userScore == null) "Rate (0–100)" else "Change rating") }
    }
}

@Composable
fun RatingDialog(initial: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val score = parseScore(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your score") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = (score ?: initial).toFloat(),
                    onValueChange = { text = it.toInt().toString() },
                    valueRange = 0f..100f,
                    steps = 99,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(3) },
                    singleLine = true,
                    isError = score == null,
                    label = { Text("0 to 100") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { score?.let(onSave) }, enabled = score != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
