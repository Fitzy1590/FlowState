package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.background.UpcomingItem
import com.fitzy.flowstate.background.UpcomingService
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.LibraryItemView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The user's library, most recently active first, and the next episodes to air. Call [refresh] when the tab is shown. */
class LibraryPresenter(
    private val repo: FlowRepository,
    private val upcomingService: UpcomingService,
    private val scope: CoroutineScope,
) {
    private val _items = MutableStateFlow<List<LibraryItemView>>(emptyList())
    val items: StateFlow<List<LibraryItemView>> = _items.asStateFlow()

    private val _upcoming = MutableStateFlow<List<UpcomingItem>>(emptyList())
    val upcoming: StateFlow<List<UpcomingItem>> = _upcoming.asStateFlow()

    fun refresh() {
        _items.value = repo.libraryItems()
        scope.launch { _upcoming.value = upcomingService.load() }
    }
}
