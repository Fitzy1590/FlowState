package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.data.local.LibraryItemView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's library, most recently active first. Call [refresh] when the tab is shown. */
class LibraryPresenter(private val repo: FlowRepository) {
    private val _items = MutableStateFlow<List<LibraryItemView>>(emptyList())
    val items: StateFlow<List<LibraryItemView>> = _items.asStateFlow()

    fun refresh() { _items.value = repo.libraryItems() }
}
