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
import com.fitzy.flowstate.presenter.SyncPresenter
import com.fitzy.flowstate.presenter.UpdatePresenter
import com.fitzy.flowstate.presenter.UpdateState
import com.fitzy.flowstate.presenter.BackupPresenter
import com.fitzy.flowstate.presenter.BackupState
import com.fitzy.flowstate.presenter.Player
import com.fitzy.flowstate.presenter.SettingsState
import com.fitzy.flowstate.presenter.SyncState
import com.fitzy.flowstate.secrets.SecretNames

@Composable
fun SettingsRoute(presenter: SettingsPresenter, sync: SyncPresenter, updates: UpdatePresenter, backup: BackupPresenter) {
    LaunchedEffect(Unit) { backup.refresh() }
    val backupState by backup.state.collectAsState()
    val state by presenter.state.collectAsState()
    val syncState by sync.state.collectAsState()
    val updateState by updates.state.collectAsState()
    SettingsScreen(state, presenter::save, presenter::clear, presenter::setDefaultPlayer, presenter::removeActor, presenter::setNotificationsEnabled, presenter::dismissMessage, syncState, sync::login, sync::syncNow, sync::logout, updateState, updates::checkNow, updates::install, updates::dismissMessage, backupState, backup::export, backup::import, sync::forcePull, sync::forcePush)
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onSave: (String, String) -> Boolean,
    onClear: (String) -> Unit,
    onPlayer: (Player) -> Unit = {},
    onRemoveActor: (Long) -> Unit = {},
    onNotifications: (Boolean) -> Unit = {},
    onDismissSettingsMessage: () -> Unit = {},
    sync: SyncState = SyncState(),
    onLogin: (String, String) -> Unit = { _, _ -> },
    onSync: () -> Unit = {},
    onLogout: () -> Unit = {},
    update: UpdateState = UpdateState("0.0.0"),
    onCheckUpdate: () -> Unit = {},
    onInstall: () -> Unit = {},
    onDismissUpdateMessage: () -> Unit = {},
    backup: BackupState = BackupState(),
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onForcePull: () -> Unit = {},
    onForcePush: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("API keys", style = MaterialTheme.typography.headlineSmall)
        Text("Keys are encrypted on this device and never shown again after saving.")
        KeyField("TMDB read access token", "Needed for real posters and recommendations. Use the long v4 token.", SecretNames.TMDB, state.tmdbSet, onSave, onClear)
        KeyField("Claude API key", "Needed for AI search and episode summaries.", SecretNames.CLAUDE, state.claudeSet, onSave, onClear)
        KeyField("YouTube Data API key", "Optional. Lets season recaps open the top result.", SecretNames.YOUTUBE, state.youtubeSet, onSave, onClear)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("New episode notifications", style = MaterialTheme.typography.titleMedium)
                Text("A notification on the day a new episode of a show in your library airs. Checked about once an hour.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.notifications, onCheckedChange = onNotifications)
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        PlayerSection(state, onPlayer)
        ActorsSection(state, onRemoveActor)
        StremioSection(sync, onLogin, onSync, onLogout, onForcePull, onForcePush)
        BackupSection(backup, onExport, onImport)
        UpdateSection(update, onCheckUpdate, onInstall, onDismissUpdateMessage)
        KeyField("OMDb API key", "Optional. Shows IMDb and Rotten Tomatoes scores. Free key from omdbapi.com.", SecretNames.OMDB, state.omdbSet, onSave, onClear)
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

@Composable
private fun StremioSection(sync: SyncState, onLogin: (String, String) -> Unit, onSync: () -> Unit, onLogout: () -> Unit, onForcePull: () -> Unit, onForcePush: () -> Unit) {
    var confirm by remember { mutableStateOf<Boolean?>(null) } // true = pull, false = push
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Stremio account", style = MaterialTheme.typography.titleMedium)
        Text("Syncs movie progress both ways, latest activity wins. Shows are pulled only. Your password is not stored.", style = MaterialTheme.typography.bodySmall)
        if (sync.connected) {
            Text("Connected", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSync, enabled = !sync.busy) { Text(if (sync.busy) "Syncing" else "Sync now") }
                OutlinedButton(onClick = onLogout) { Text("Disconnect") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirm = true }, enabled = !sync.busy) { Text("Force pull") }
                TextButton(onClick = { confirm = false }, enabled = !sync.busy) { Text("Force push") }
            }
        } else {
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") })
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(onClick = { onLogin(email, password); password = "" }, enabled = email.isNotBlank() && password.isNotBlank() && !sync.busy) {
                Text(if (sync.busy) "Connecting" else "Connect")
            }
        }
        confirm?.let { pull ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(if (pull) "Force pull from Stremio?" else "Force push to Stremio?") },
                text = { Text(if (pull) "Stremio wins for every title that differs. Progress made only on this device is overwritten." else "This app wins for every movie that differs. Stremio's copy is overwritten.") },
                confirmButton = { Button(onClick = { if (pull) onForcePull() else onForcePush(); confirm = null }) { Text("Continue") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            )
        }
        sync.message?.let { Text(it) }
        sync.log.take(5).forEach { Text("${it.action}: ${it.item}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun UpdateSection(u: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("App updates", style = MaterialTheme.typography.titleMedium)
        Text("Version ${u.currentVersion}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCheck, enabled = !u.checking && u.progress == null) { Text(if (u.checking) "Checking" else "Check for updates") }
            u.available?.let { Button(onClick = onInstall, enabled = u.progress == null) { Text("Update to ${it.version}") } }
        }
        u.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        u.message?.let { Text(it) }
    }
}

/** Shown at launch when a newer version is found, unless that version was skipped. */
@Composable
fun UpdateDialog(presenter: UpdatePresenter) {
    val state by presenter.state.collectAsState()
    val info = state.available
    if (!state.prompt || info == null) return
    AlertDialog(
        onDismissRequest = presenter::later,
        title = { Text("Update available: ${info.version}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("You have ${state.currentVersion}.")
                if (info.notes.isNotBlank()) Text(info.notes.take(600), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = presenter::install) { Text("Update now") } },
        dismissButton = {
            Row {
                TextButton(onClick = presenter::later) { Text("Later") }
                TextButton(onClick = presenter::skipVersion) { Text("Skip this version") }
            }
        },
    )
}

@Composable
private fun PlayerSection(state: SettingsState, onPlayer: (Player) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Default player", style = MaterialTheme.typography.titleMedium)
        Text("Used by the main Play button on a title page. Nuvio opens the title page, not a specific episode.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Player.entries.forEach { p -> FilterChip(selected = state.defaultPlayer == p, onClick = { onPlayer(p) }, label = { Text(p.label) }) }
        }
    }
}

@Composable
private fun ActorsSection(state: SettingsState, onRemove: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Actors you like and dislike", style = MaterialTheme.typography.titleMedium)
        Text("Mark actors from the Cast tab of any title. Disliked actors lower a title in your recommendations but never hide it.", style = MaterialTheme.typography.bodySmall)
        if (state.actors.isEmpty()) Text("None yet.")
        state.actors.forEach { a ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${a.name.ifBlank { "Actor ${a.id}" }}  ·  ${if (a.pref == com.fitzy.flowstate.data.local.ActorPref.LIKED) "Liked" else "Disliked"}", Modifier.weight(1f))
                TextButton(onClick = { onRemove(a.id) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun BackupSection(b: BackupState, onExport: () -> Unit, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Text("Saves your library, scores, votes, actors and watched episodes to a file. API keys and your Stremio login are never included.", style = MaterialTheme.typography.bodySmall)
        if (b.available) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport) { Text("Export backup") }
                OutlinedButton(onClick = onImport) { Text("Restore from file") }
            }
        } else Text("Not available on this device.")
        b.message?.let { Text(it) }
    }
}
