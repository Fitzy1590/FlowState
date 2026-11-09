package com.fitzy.flowstate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.background.UpcomingItem
import com.fitzy.flowstate.data.local.LibraryItemView
import com.fitzy.flowstate.data.local.LibraryStatus
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.presenter.LibraryPresenter

@Composable
fun LibraryRoute(p: LibraryPresenter, onOpen: (Candidate) -> Unit) {
    LaunchedEffect(Unit) { p.refresh() }
    val items by p.items.collectAsState()
    val upcoming by p.upcoming.collectAsState()
    LibraryScreen(items, upcoming, onOpen)
}

private enum class LibraryFilter(val label: String) {
    All("All"), InProgress("In progress"), Planned("Planned"), Watched("Watched");

    fun matches(s: LibraryStatus) = when (this) {
        All -> true
        InProgress -> s == LibraryStatus.WATCHING
        Planned -> s == LibraryStatus.PLANNED
        Watched -> s == LibraryStatus.COMPLETED
    }
}

@Composable
fun LibraryScreen(items: List<LibraryItemView>, upcoming: List<UpcomingItem>, onOpen: (Candidate) -> Unit) {
    var filter by remember { mutableStateOf(LibraryFilter.All) }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Your library is empty", style = MaterialTheme.typography.titleLarge)
            Text("Add titles from Discover, Search or a title page.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (upcoming.isNotEmpty()) {
            item { Text("Upcoming episodes", style = MaterialTheme.typography.titleLarge) }
            items(upcoming, key = { "up-${it.show.id.tmdbId}-${it.episode.season}-${it.episode.episode}" }) { u ->
                Row(Modifier.fillMaxWidth().focusHighlight().clickable { onOpen(u.show) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.show.name, style = MaterialTheme.typography.titleSmall)
                        Text("S${u.episode.season}E${u.episode.episode}${if (u.episode.name.isNotBlank()) "  ·  ${u.episode.name}" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(u.episode.airDate, color = MaterialTheme.colorScheme.primary)
                }
            }
            item { Text("Your library", style = MaterialTheme.typography.titleLarge) }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryFilter.entries) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = {
                        Text("${f.label} (${items.count { f.matches(it.status) }})")
                    })
                }
            }
        }
        items(items.filter { filter.matches(it.status) }, key = { "${it.candidate.id.type}-${it.candidate.id.tmdbId}" }) { v ->
            Row(Modifier.fillMaxWidth().focusHighlight().clickable { onOpen(v.candidate) }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PosterCard(v.candidate, Modifier.width(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(v.candidate.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (v.status) {
                            LibraryStatus.PLANNED -> "Planned"
                            LibraryStatus.WATCHING -> "Watching  ·  ${(v.completion * 100).toInt()}%"
                            LibraryStatus.COMPLETED -> "Watched"
                            LibraryStatus.DROPPED -> "Dropped"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (v.status == LibraryStatus.WATCHING) LinearProgressIndicator(progress = { v.completion.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
