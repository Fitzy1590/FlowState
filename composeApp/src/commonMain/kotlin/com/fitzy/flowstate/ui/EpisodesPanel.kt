package com.fitzy.flowstate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.presenter.EpisodeRow
import com.fitzy.flowstate.presenter.EpisodesPresenter
import com.fitzy.flowstate.presenter.EpisodesState

@Composable
fun EpisodesRoute(p: EpisodesPresenter) {
    val state by p.state.collectAsState()
    EpisodesPanel(
        state, p::select, p::toggleWatched, p::requestSummary, p::catchUp,
        p::openSeasonTrailer, p::openRecap, p::dismissMessage,
    )
}

/** Episode descriptions are never shown for unwatched episodes, so nothing here spoils the story. */
@Composable
fun EpisodesPanel(
    state: EpisodesState,
    onSelect: (Int) -> Unit,
    onToggle: (Int, Int) -> Unit,
    onSummary: (Int, Int) -> Unit,
    onCatchUp: () -> Unit,
    onTrailer: (Int) -> Unit,
    onRecap: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading) { Text("Loading"); return@Column }
        if (state.seasons.isEmpty()) { Text("No episode information available."); return@Column }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.seasons, key = { it.number }) { s ->
                FilterChip(selected = state.selected == s.number, onClick = { onSelect(s.number) }, label = { Text("Season ${s.number}") })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.plan.trailerSeason?.let { OutlinedButton(onClick = { onTrailer(it) }) { Text("Season $it trailer") } }
            state.plan.recapSeason?.let { OutlinedButton(onClick = { onRecap(it) }) { Text("Season $it recap") } }
        }
        if (state.rows.any { it.watched }) {
            FilledTonalButton(onClick = onCatchUp, enabled = !state.catchUpBusy) {
                Text(if (state.catchUpBusy) "Working" else "Catch me up")
            }
        }
        state.catchUp?.let { text ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Catch-up so far", color = MaterialTheme.colorScheme.primary)
                    Text(text)
                    Text("AI-generated", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        state.rows.forEach { EpisodeItem(it, onToggle, onSummary) }
    }
    state.message?.let {
        AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }, text = { Text(it) })
    }
}

@Composable
private fun EpisodeItem(row: EpisodeRow, onToggle: (Int, Int) -> Unit, onSummary: (Int, Int) -> Unit) {
    val e = row.info
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.watched, onCheckedChange = { onToggle(e.season, e.number) })
            Text("E${e.number}  ${e.name}", Modifier.weight(1f))
            if (row.watched && row.summary == null) {
                TextButton(onClick = { onSummary(e.season, e.number) }, enabled = !row.summaryBusy) {
                    Text(if (row.summaryBusy) "Working" else "Summary")
                }
            }
        }
        row.summary?.let {
            Column(Modifier.padding(start = 48.dp, bottom = 8.dp)) {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Text("AI-generated", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
