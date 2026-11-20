package com.doan.studyhub.user.ui.search

import com.doan.studyhub.core.data.model.SearchResult
import com.doan.studyhub.core.data.model.SearchTab
import com.doan.studyhub.core.data.repository.SearchRepository

enum class SearchPhase { IDLE, LOADING, CONTENT, EMPTY, ERROR }

/** Results of one tab for the current query. Errors hold safe, user-facing text only. */
data class SearchTabState(
    val items: List<SearchResult> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null
)

data class SearchUiState(
    val query: String = "",
    val selected: SearchTab = SearchTab.USERS,
    val tabs: Map<SearchTab, SearchTabState> = emptyTabs(),
    val followBusy: Set<String> = emptySet(),
    val message: String? = null
) {
    val tab: SearchTabState get() = tabs.getValue(selected)
    val searchable: Boolean get() = query.length >= SearchRepository.MIN_QUERY_LENGTH

    val phase: SearchPhase
        get() = when {
            !searchable -> SearchPhase.IDLE
            tab.loading -> SearchPhase.LOADING
            tab.error != null -> SearchPhase.ERROR
            tab.loaded && tab.items.isEmpty() -> SearchPhase.EMPTY
            tab.loaded -> SearchPhase.CONTENT
            else -> SearchPhase.LOADING
        }

    companion object {
        fun emptyTabs(): Map<SearchTab, SearchTabState> = SearchTab.entries.associateWith { SearchTabState() }
    }
}
