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
import com.fitzy.flowstate.presenter.NuvioPresenter
import com.fitzy.flowstate.presenter.NuvioStage
import com.fitzy.flowstate.presenter.NuvioState
import com.fitzy.flowstate.presenter.Player
import com.fitzy.flowstate.sync.nuvio.NuvioProfile
import com.fitzy.flowstate.presenter.SettingsState
import com.fitzy.flowstate.presenter.SyncState
import com.fitzy.flowstate.secrets.SecretNames

@Composable
fun SettingsRoute(presenter: SettingsPresenter, sync: SyncPresenter, updates: UpdatePresenter, backup: BackupPresenter, nuvio: NuvioPresenter) {
    val nuvioState by nuvio.state.collectAsState()
    LaunchedEffect(Unit) { backup.refresh() }
    val backupState by backup.state.collectAsState()
    val state by presenter.state.collectAsState()
    val syncState by sync.state.collectAsState()
    val updateState by updates.state.collectAsState()
    SettingsScreen(state, presenter::save, presenter::clear, presenter::setDefaultPlayer, presenter::removeActor, presenter::setNotificationsEnabled, presenter::dismissMessage, presenter::openLink, syncState, sync::login, sync::syncNow, sync::logout, updateState, updates::checkNow, updates::install, updates::dismissMessage, updates::setAuto, backupState, backup::export, backup::import, sync::forcePull, sync::forcePush, nuvioState, nuvio::findServer, nuvio::signIn, nuvio::chooseProfile, nuvio::syncNow, nuvio::logout)
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
    onOpenLink: (String) -> Unit = {},
    sync: SyncState = SyncState(),
    onLogin: (String, String) -> Unit = { _, _ -> },
    onSync: () -> Unit = {},
    onLogout: () -> Unit = {},
    update: UpdateState = UpdateState("0.0.0"),
    onCheckUpdate: () -> Unit = {},
    onInstall: () -> Unit = {},
    onDismissUpdateMessage: () -> Unit = {},
    onAutoUpdate: (Boolean) -> Unit = {},
    backup: BackupState = BackupState(),
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onForcePull: () -> Unit = {},
    onForcePush: () -> Unit = {},
    nuvioState: NuvioState = NuvioState(),
    onNuvioFind: (String) -> Unit = {},
    onNuvioSignIn: (String, String) -> Unit = { _, _ -> },
    onNuvioProfile: (NuvioProfile) -> Unit = {},
    onNuvioSync: () -> Unit = {},
    onNuvioLogout: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("API keys", style = MaterialTheme.typography.headlineSmall)
        Text("Keys are encrypted on this device and never shown again after saving.")
        KeyField("TMDB read access token", "Needed for real posters and recommendations. On the TMDB page, copy the long \"API Read Access Token\", not the short API key.", SecretNames.TMDB, state.tmdbSet, onSave, onClear, HelpLinks.TMDB_KEY, onOpenLink)
        KeyField("Claude API key", "Needed for AI search and episode summaries. Usage is billed to your Anthropic account.", SecretNames.CLAUDE, state.claudeSet, onSave, onClear, HelpLinks.CLAUDE_KEY, onOpenLink)
        KeyField("YouTube Data API key", "Optional. Lets season recaps open the top result. Enable the YouTube Data API v3 for your Google project, then create a key.", SecretNames.YOUTUBE, state.youtubeSet, onSave, onClear, HelpLinks.YOUTUBE_KEY, onOpenLink, HelpLinks.YOUTUBE_GUIDE)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("New episode notifications", style = MaterialTheme.typography.titleMedium)
                Text("A notification on the day a new episode of a show in your library airs. Checked about once an hour.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.notifications, onCheckedChange = onNotifications)
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        PlayerSection(state, onPlayer, onOpenLink)
        ActorsSection(state, onRemoveActor)
        StremioSection(sync, onLogin, onSync, onLogout, onForcePull, onForcePush, onOpenLink)
        NuvioSection(nuvioState, onNuvioFind, onNuvioSignIn, onNuvioProfile, onNuvioSync, onNuvioLogout, onOpenLink)
        BackupSection(backup, onExport, onImport)
        UpdateSection(update, onCheckUpdate, onInstall, onDismissUpdateMessage, onAutoUpdate, onOpenLink)
        KeyField("OMDb API key", "Optional. Shows IMDb and Rotten Tomatoes scores. The free key is emailed to you.", SecretNames.OMDB, state.omdbSet, onSave, onClear, HelpLinks.OMDB_KEY, onOpenLink)
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
    getKeyUrl: String? = null,
    onOpenLink: (String) -> Unit = {},
    guideUrl: String? = null,
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
            if (getKeyUrl != null) TextButton(onClick = { onOpenLink(getKeyUrl) }) { Text("Get key") }
            if (guideUrl != null) TextButton(onClick = { onOpenLink(guideUrl) }) { Text("Guide") }
        }
    }
}

@Composable
private fun StremioSection(sync: SyncState, onLogin: (String, String) -> Unit, onSync: () -> Unit, onLogout: () -> Unit, onForcePull: () -> Unit, onForcePush: () -> Unit, onOpenLink: (String) -> Unit) {
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
            TextButton(onClick = { onOpenLink(HelpLinks.STREMIO) }) { Text("Create a Stremio account") }
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
private fun UpdateSection(u: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit, onDismiss: () -> Unit, onAuto: (Boolean) -> Unit, onOpenLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("App updates", style = MaterialTheme.typography.titleMedium)
        Text("Version ${u.currentVersion}", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Install updates automatically")
                Text(
                    "Checks when the app opens (at most every 6 hours) and installs a new version without asking. " +
                        "Android may still show its own install confirmation. Windows asks for administrator permission.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = u.auto, onCheckedChange = onAuto)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCheck, enabled = !u.checking && u.progress == null) { Text(if (u.checking) "Checking" else "Check for updates") }
            u.available?.let { Button(onClick = onInstall, enabled = u.progress == null) { Text("Update to ${it.version}") } }
            TextButton(onClick = { onOpenLink(HelpLinks.RELEASES) }) { Text("All releases") }
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
private fun PlayerSection(state: SettingsState, onPlayer: (Player) -> Unit, onOpenLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Default player", style = MaterialTheme.typography.titleMedium)
        Text("Used by the main Play button on a title page. Nuvio opens the title page, not a specific episode.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Player.entries.forEach { p -> FilterChip(selected = state.defaultPlayer == p, onClick = { onPlayer(p) }, label = { Text(p.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onOpenLink(HelpLinks.STREMIO) }) { Text("Get Stremio") }
            TextButton(onClick = { onOpenLink(HelpLinks.NUVIO) }) { Text("Get Nuvio") }
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

@Composable
private fun NuvioSection(
    n: NuvioState,
    onFind: (String) -> Unit,
    onSignIn: (String, String) -> Unit,
    onProfile: (NuvioProfile) -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Nuvio account (self-hosted server)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Syncs watched movies and episodes both ways: anything watched on either side ends up on both, and nothing is deleted. " +
                "Works with a self-hosted Nuvio server. The official hosted Nuvio service cannot be used because its connection details are not published. Your password is not stored.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { onOpenLink(HelpLinks.NUVIO) }) { Text("Nuvio setup and self-hosting") }
        when (n.stage) {
            NuvioStage.DISCONNECTED -> {
                OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server address") }, placeholder = { Text("nuvio.example.com") })
                Button(onClick = { onFind(address) }, enabled = address.isNotBlank() && !n.busy) { Text(if (n.busy) "Looking" else "Find server") }
            }
            NuvioStage.SERVER_FOUND -> {
                Text("Server found: ${n.serverHost}", color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") })
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                Button(onClick = { onSignIn(email, password); password = "" }, enabled = email.isNotBlank() && password.isNotEmpty() && !n.busy) { Text(if (n.busy) "Signing in" else "Sign in") }
            }
            NuvioStage.CHOOSE_PROFILE -> {
                Text("Which profile should sync?")
                n.profiles.forEach { p -> OutlinedButton(onClick = { onProfile(p) }, enabled = !n.busy) { Text(p.name.ifBlank { "Profile ${p.index}" }) } }
            }
            NuvioStage.CONNECTED -> {
                Text("Connected to ${n.serverHost}${if (n.profileName.isNotBlank()) " as ${n.profileName}" else ""}", color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSync, enabled = !n.busy) { Text(if (n.busy) "Syncing" else "Sync now") }
                    OutlinedButton(onClick = onLogout) { Text("Disconnect") }
                }
            }
        }
        n.message?.let { Text(it) }
        n.log.take(5).forEach { Text("${it.action}: ${it.item}", style = MaterialTheme.typography.bodySmall) }
    }
}
