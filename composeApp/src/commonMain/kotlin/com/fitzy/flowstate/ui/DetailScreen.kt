package com.fitzy.flowstate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.data.local.ActorPref
import com.fitzy.flowstate.data.tmdb.CastMember
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.links.parseScore
import com.fitzy.flowstate.presenter.DetailPresenter
import com.fitzy.flowstate.presenter.DetailState
import com.fitzy.flowstate.presenter.EpisodesPresenter
import com.fitzy.flowstate.presenter.Player
import com.fitzy.flowstate.theme.FlowColors

@Composable
fun DetailRoute(presenter: DetailPresenter, episodes: EpisodesPresenter?, onBack: () -> Unit, onOpen: (Candidate) -> Unit) {
    val state by presenter.state.collectAsState()
    DetailScreen(
        state, onBack,
        onRate = presenter::rate, onAdd = presenter::addToLibrary,
        onPlay = presenter::play, onPlayDefault = presenter::play, onTrailer = presenter::watchTrailer,
        onLetterboxd = presenter::openLetterboxd, onDismissMessage = presenter::dismissMessage,
        onActor = presenter::setActor, onOpen = onOpen, onTab = presenter::refresh,
        episodes = episodes?.let { ep -> { EpisodesRoute(ep) } },
    )
}

/** Two panes when wide (poster and actions left; overview, cast, similar and episodes right); one column when narrow. Esc goes back. */
@Composable
fun DetailScreen(
    state: DetailState,
    onBack: () -> Unit,
    onRate: (Int) -> Boolean,
    onAdd: () -> Unit,
    onPlay: (Player) -> Unit,
    onPlayDefault: () -> Unit,
    onTrailer: () -> Unit,
    onLetterboxd: () -> Unit,
    onDismissMessage: () -> Unit,
    onActor: (CastMember, ActorPref) -> Unit = { _, _ -> },
    onOpen: (Candidate) -> Unit = {},
    onTab: () -> Unit = {},
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
            val actions: @Composable () -> Unit = { Actions(state, { rating = true }, onAdd, onPlay, onPlayDefault, onTrailer, onLetterboxd) }
            val right: @Composable (Modifier) -> Unit = { m -> RightPane(state, episodes, onActor, onOpen, onTab, m) }
            if (maxWidth >= 700.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PosterCard(state.candidate, Modifier.fillMaxWidth())
                        actions()
                    }
                    right(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PosterCard(state.candidate, Modifier.width(200.dp))
                    actions()
                    right(Modifier.fillMaxWidth().heightIn(min = 400.dp))
                }
            }
        }
    }

    if (rating) RatingDialog(state.userScore ?: 70, onSave = { if (onRate(it)) rating = false }, onDismiss = { rating = false })
    state.message?.let {
        AlertDialog(onDismissRequest = onDismissMessage, confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } }, text = { Text(it) })
    }
}

@Composable
private fun RightPane(
    state: DetailState,
    episodes: (@Composable () -> Unit)?,
    onActor: (CastMember, ActorPref) -> Unit,
    onOpen: (Candidate) -> Unit,
    onTab: () -> Unit,
    modifier: Modifier,
) {
    val tabs = buildList { add("Overview"); add("Cast"); add("Similar"); if (episodes != null) add("Episodes") }
    var tab by remember { mutableStateOf(0) }
    Column(modifier) {
        ScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 0.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i; onTab() }, text = { Text(t) }) }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tabs[tab]) {
                "Overview" -> Overview(state)
                "Cast" -> CastList(state, onActor)
                "Similar" -> SimilarRow(state, onOpen)
                else -> episodes?.invoke()
            }
        }
    }
}

@Composable
private fun Overview(state: DetailState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.candidate.name, style = MaterialTheme.typography.headlineMedium)
        Text(
            listOfNotNull(state.candidate.year?.toString(), "TMDB ${"%.1f".format(state.candidate.voteAverage)}".takeIf { state.candidate.voteAverage > 0 }).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            state.userScore?.let { Badge("Your score $it", FlowColors.UserScore) }
            state.ratings?.imdb?.let { Badge("IMDb ${"%.1f".format(it)}", FlowColors.ImdbBadge) }
            state.ratings?.rottenTomatoes?.let { Badge("Rotten Tomatoes $it%", RottenTomatoes) }
            if (state.userScore == null && state.ratings == null) Text("Not rated yet")
        }
        when {
            state.loading -> Text("Loading")
            state.overview.isNotBlank() -> Text(state.overview)
            else -> Text("No description available.")
        }
    }
}

private val RottenTomatoes = androidx.compose.ui.graphics.Color(0xFFFA320A)

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = androidx.compose.ui.graphics.Color(0xFF0C0D10), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CastList(state: DetailState, onActor: (CastMember, ActorPref) -> Unit) {
    if (state.cast.isEmpty()) { Text(if (state.loading) "Loading" else "No cast information available."); return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Mark actors you like or dislike. Liked actors raise a title in your recommendations; disliked ones lower it.", style = MaterialTheme.typography.bodySmall)
        state.cast.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall)
                    if (m.character.isNotBlank()) Text(m.character, style = MaterialTheme.typography.bodySmall)
                }
                FilterChip(selected = m.id in state.likedActors, onClick = { onActor(m, ActorPref.LIKED) }, label = { Text("Like") })
                FilterChip(selected = m.id in state.dislikedActors, onClick = { onActor(m, ActorPref.DISLIKED) }, label = { Text("Dislike") })
            }
        }
    }
}

@Composable
private fun SimilarRow(state: DetailState, onOpen: (Candidate) -> Unit) {
    if (state.similar.isEmpty()) { Text(if (state.loading) "Loading" else "No similar titles found."); return }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.similar, key = { "${it.id.type}-${it.id.tmdbId}" }) { c -> PosterCard(c, Modifier.width(120.dp), onClick = { onOpen(c) }) }
    }
}

@Composable
private fun Actions(
    state: DetailState,
    onRate: () -> Unit,
    onAdd: () -> Unit,
    onPlay: (Player) -> Unit,
    onPlayDefault: () -> Unit,
    onTrailer: () -> Unit,
    onLetterboxd: () -> Unit,
) {
    val other = Player.entries.first { it != state.defaultPlayer }
    val playLabel = state.nextEpisode?.let { (s, e) -> "Play S${s}E$e in ${state.defaultPlayer.label}" } ?: "Play in ${state.defaultPlayer.label}"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPlayDefault, modifier = Modifier.fillMaxWidth()) { Text(playLabel) }
        OutlinedButton(onClick = { onPlay(other) }, modifier = Modifier.fillMaxWidth()) { Text("Open in ${other.label}") }
        OutlinedButton(onClick = onTrailer, modifier = Modifier.fillMaxWidth()) { Text("Watch trailer") }
        FilledTonalButton(onClick = onAdd, enabled = !state.inLibrary, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.inLibrary) "In your library" else "Add to library")
        }
        OutlinedButton(onClick = onRate, modifier = Modifier.fillMaxWidth()) { Text(if (state.userScore == null) "Rate (0–100)" else "Change rating") }
        TextButton(onClick = onLetterboxd, modifier = Modifier.fillMaxWidth()) { Text("Letterboxd") }
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
                Slider(value = (score ?: initial).toFloat(), onValueChange = { text = it.toInt().toString() }, valueRange = 0f..100f, steps = 99)
                OutlinedTextField(value = text, onValueChange = { text = it.take(3) }, singleLine = true, isError = score == null, label = { Text("0 to 100") })
            }
        },
        confirmButton = { TextButton(onClick = { score?.let(onSave) }, enabled = score != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
