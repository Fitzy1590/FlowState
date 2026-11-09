package com.fitzy.flowstate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.presenter.SearchPresenter
import com.fitzy.flowstate.presenter.SearchState

@Composable
fun SearchRoute(p: SearchPresenter, onOpen: (Candidate) -> Unit) {
    val state by p.state.collectAsState()
    SearchScreen(state, p::setText, p::submit, p::removeChip, p::add, onOpen, p::startVoice, p::stopVoice, p::dismissMessage)
}

@Composable
fun SearchScreen(
    state: SearchState,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onRemoveChip: (String) -> Unit,
    onAdd: (Candidate) -> Unit,
    onOpen: (Candidate) -> Unit,
    onVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What do you feel like watching?", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.text, onValueChange = onText, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Old popular war movies, or something like Band of Brothers") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            )
            if (state.voiceAvailable) {
                OutlinedButton(onClick = if (state.listening) onStopVoice else onVoice) { Text(if (state.listening) "Stop" else "Speak") }
            }
            Button(onClick = onSubmit, enabled = state.text.isNotBlank() && !state.loading) { Text("Search") }
        }
        if (state.listening) Text("Listening", color = MaterialTheme.colorScheme.primary)
        if (!state.voiceAvailable) Text("Tip: press Win+H on Windows to dictate.", style = MaterialTheme.typography.bodySmall)

        if (state.chips.isNotEmpty()) {
            Text(if (state.usedAi) "Understood by AI. Tap a filter to remove it." else "Basic search. Tap a filter to remove it.", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.chips, key = { it.key }) { c ->
                    FilterChip(selected = true, onClick = { onRemoveChip(c.key) }, label = { Text("${c.label}  ×") })
                }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.results, key = { "${it.id.type}-${it.id.tmdbId}" }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(c) }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PosterCard(c, Modifier.width(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(c.year?.toString(), if (c.voteAverage > 0) "TMDB ${"%.1f".format(c.voteAverage)}" else null).joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onAdd(c) }) { Text("Add") }
                }
            }
        }
    }
}
