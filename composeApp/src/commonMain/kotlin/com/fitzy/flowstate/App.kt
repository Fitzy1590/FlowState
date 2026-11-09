package com.fitzy.flowstate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.fitzy.flowstate.app.AppContainer
import com.fitzy.flowstate.theme.FlowStateTheme
import com.fitzy.flowstate.ui.DiscoverRoute
import com.fitzy.flowstate.presenter.DetailPresenter
import com.fitzy.flowstate.ui.DetailRoute
import com.fitzy.flowstate.ui.HomeRoute
import com.fitzy.flowstate.ui.PlatformBackHandler
import com.fitzy.flowstate.ui.SettingsRoute

private enum class Tab(val label: String) { Home("Home"), Discover("Discover"), Settings("Settings") }

/** Top-level shell. Navigation is a shared bottom bar for now; per-platform navigation comes with the platform UI stages. */
@Composable
fun App(container: AppContainer) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var detail by remember { mutableStateOf<DetailPresenter?>(null) }
    LaunchedEffect(container) {
        container.home.load()
        container.discover.load()
    }
    FlowStateTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(t.label) })
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                val open = detail
                if (open != null) {
                    PlatformBackHandler { detail = null }
                    DetailRoute(open, onBack = { detail = null })
                    return@Column
                }
                when (tab) {
                    Tab.Home -> HomeRoute(container.home, onOpen = { c -> detail = container.detailFor(c).also { it.load() } })
                    Tab.Discover -> DiscoverRoute(container.discover)
                    Tab.Settings -> SettingsRoute(container.settings)
                }
            }
        }
    }
}
