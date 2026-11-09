package com.fitzy.flowstate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.presenter.SettingsPresenter
import com.fitzy.flowstate.presenter.SettingsState
import com.fitzy.flowstate.secrets.SecretNames

@Composable
fun SettingsRoute(presenter: SettingsPresenter) {
    val state by presenter.state.collectAsState()
    SettingsScreen(state, presenter::save, presenter::clear)
}

@Composable
fun SettingsScreen(state: SettingsState, onSave: (String, String) -> Boolean, onClear: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("API keys", style = MaterialTheme.typography.headlineSmall)
        Text("Keys are encrypted on this device and never shown again after saving.")
        KeyField("TMDB read access token", "Needed for real posters and recommendations. Use the long v4 token.", SecretNames.TMDB, state.tmdbSet, onSave, onClear)
        KeyField("Claude API key", "Needed for AI search and episode summaries.", SecretNames.CLAUDE, state.claudeSet, onSave, onClear)
        KeyField("YouTube Data API key", "Optional. Lets season recaps open the top result.", SecretNames.YOUTUBE, state.youtubeSet, onSave, onClear)
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun KeyField(
    label: String,
    help: String,
    name: String,
    isSet: Boolean,
    onSave: (String, String) -> Boolean,
    onClear: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(help, style = MaterialTheme.typography.bodySmall)
        Text(if (isSet) "Saved" else "Not set", color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (isSet) "Replace key" else "Paste key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (onSave(name, text)) text = "" }, enabled = text.isNotBlank()) { Text("Save") }
            if (isSet) OutlinedButton(onClick = { onClear(name) }) { Text("Remove") }
        }
    }
}
