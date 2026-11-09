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
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.presenter.DetailPresenter
import com.fitzy.flowstate.presenter.EpisodesPresenter
import com.fitzy.flowstate.ui.DetailRoute
import com.fitzy.flowstate.ui.HomeRoute
import com.fitzy.flowstate.ui.LibraryRoute
import com.fitzy.flowstate.ui.SearchRoute
import com.fitzy.flowstate.ui.PlatformBackHandler
import com.fitzy.flowstate.ui.SettingsRoute
import com.fitzy.flowstate.ui.UpdateDialog

private enum class Tab(val label: String) { Home("Home"), Discover("Discover"), Search("Search"), Library("Library"), Settings("Settings") }

/** Top-level shell. Navigation is a shared bottom bar for now; per-platform navigation comes with the platform UI stages. */
@Composable
fun App(container: AppContainer) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var detail by remember { mutableStateOf<Pair<DetailPresenter, EpisodesPresenter?>?>(null) }
    fun openTitle(c: Candidate) {
        val eps = if (c.id.type == MediaType.SHOW) container.episodesFor(c).also { it.load() } else null
        detail = container.detailFor(c).also { it.load() } to eps
    }
    LaunchedEffect(container) {
        container.home.load()
        container.discover.load()
        container.sync.syncIfConnected()
        container.updates.checkOnLaunch()
    }
    FlowStateTheme {
        UpdateDialog(container.updates)
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
                    DetailRoute(open.first, open.second, onBack = { detail = null }, onOpen = ::openTitle)
                    return@Column
                }
                when (tab) {
                    Tab.Home -> HomeRoute(container.home, onOpen = ::openTitle)
                    Tab.Discover -> DiscoverRoute(container.discover)
                    Tab.Search -> SearchRoute(container.search, onOpen = ::openTitle)
                    Tab.Library -> LibraryRoute(container.library, onOpen = ::openTitle)
                    Tab.Settings -> SettingsRoute(container.settings, container.sync, container.updates)
                }
            }
        }
    }
}
