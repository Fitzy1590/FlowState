package com.fitzy.flowstate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.data.local.LibraryEntry
import com.fitzy.flowstate.data.local.LibraryItemView
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.Vote
import com.fitzy.flowstate.presenter.HomePresenter
import com.fitzy.flowstate.presenter.HomeState

@Composable
fun HomeRoute(presenter: HomePresenter, onOpen: (Candidate) -> Unit) {
    val state by presenter.state.collectAsState()
    HomeScreen(state, onAnswer = presenter::answerPrompt, onDismiss = presenter::dismissPrompt, onOpen = onOpen)
}

@Composable
fun HomeScreen(state: HomeState, onAnswer: (Vote) -> Unit, onDismiss: () -> Unit, onOpen: (Candidate) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Hero(state.hero) }
        state.prompt?.let { item { InterestCard(it, onAnswer, onDismiss, onOpen) } }
        if (state.inProgress.isNotEmpty()) {
            item { Text("Continue watching", style = MaterialTheme.typography.titleLarge) }
            item { TitleRow(state.inProgress, onOpen, showProgress = true) }
        }
        if (state.recentlyWatched.isNotEmpty()) {
            item { Text("Watched recently", style = MaterialTheme.typography.titleLarge) }
            item { TitleRow(state.recentlyWatched, onOpen, showProgress = false) }
        }
        item { Text("Recommended for you", style = MaterialTheme.typography.titleLarge) }
        item {
            // focusRestorer: coming back to this row returns to the poster that was focused (D-pad).
            LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.recommended, key = { "${it.id.type}-${it.id.tmdbId}" }) { c -> PosterCard(c, Modifier.width(140.dp), onClick = { onOpen(c) }) }
            }
        }
    }
}

@Composable
private fun TitleRow(items: List<LibraryItemView>, onOpen: (Candidate) -> Unit, showProgress: Boolean) {
    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { "${it.candidate.id.type}-${it.candidate.id.tmdbId}" }) { v ->
            Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PosterCard(v.candidate, Modifier.fillMaxWidth(), onClick = { onOpen(v.candidate) }, progress = if (showProgress) v.completion.toFloat() else null)
                Text(v.candidate.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Hero(entry: LibraryEntry?) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry == null) {
                Text("Nothing in progress", style = MaterialTheme.typography.titleLarge)
                Text("Add a title from Discover and it will show up here.", color = MaterialTheme.colorScheme.onSurface)
            } else {
                Text("Continue watching", color = MaterialTheme.colorScheme.primary)
                Text(entry.name, style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(progress = { entry.completion.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun InterestCard(c: Candidate, onAnswer: (Vote) -> Unit, onDismiss: () -> Unit, onOpen: (Candidate) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            PosterCard(c, Modifier.width(84.dp), onClick = { onOpen(c) })
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Would this interest you?", color = MaterialTheme.colorScheme.primary)
                Text("${c.name}${c.year?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAnswer(Vote.YES) }) { Text("Yes") }
                    OutlinedButton(onClick = { onAnswer(Vote.MAYBE) }) { Text("Maybe") }
                    OutlinedButton(onClick = { onAnswer(Vote.NEVER) }) { Text("Never") }
                }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
