package com.doan.studyhub.user.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.SearchResult
import com.doan.studyhub.core.data.model.SearchTab
import com.doan.studyhub.core.data.repository.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel @JvmOverloads constructor(
    private val repository: SearchRepository = SearchRepository(),
    private val externalScope: CoroutineScope? = null,
    private val debounceMs: Long = DEBOUNCE_MS
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = mutable.asStateFlow()

    private var debounceJob: Job? = null
    // generation changes with the query; tokens change with every request of a tab.
    private var generation = 0
    private val tokens = mutableMapOf<SearchTab, Int>()
    private val requests = mutableMapOf<SearchTab, Job>()

    /** Typing: applies the query once the user pauses for [debounceMs]. */
    fun onQueryChanged(text: String) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            applyQuery(text)
        }
    }

    /** Keyboard search action: applies the query immediately. */
    fun submit(text: String) {
        debounceJob?.cancel()
        applyQuery(text, force = true)
    }

    fun selectTab(tab: SearchTab) {
        val state = mutable.value
        if (state.selected == tab) return
        mutable.value = state.copy(selected = tab)
        val target = state.tabs.getValue(tab)
        if (state.searchable && !target.loaded && !target.loading && target.error == null) loadFirst(tab)
    }

    fun retry() {
        val state = mutable.value
        if (!state.searchable) return
        if (state.tab.loadMoreError != null) loadMore() else loadFirst(state.selected)
    }

    fun refresh() {
        if (mutable.value.searchable) loadFirst(mutable.value.selected)
    }

    fun loadMore() {
        val state = mutable.value
        val tab = state.selected
        val current = state.tab
        if (!state.searchable || !current.loaded || !current.hasMore || current.loading || current.loadingMore) return
        val gen = generation
        val token = nextToken(tab)
        val query = state.query
        val offset = current.items.size
        update(tab) { it.copy(loadingMore = true, loadMoreError = null) }
        requests[tab] = scope.launch {
            val result = repository.search(tab, query, offset)
            if (gen != generation || tokens[tab] != token) return@launch
            when (result) {
                is ApiResponse.Success -> update(tab) { previous ->
                    val known = previous.items.mapTo(HashSet()) { it.key }
                    previous.copy(
                        items = previous.items + result.data.items.filter { known.add(it.key) },
                        hasMore = result.data.hasMore,
                        loadingMore = false
                    )
                }
                is ApiResponse.Failure -> update(tab) {
                    it.copy(loadingMore = false, loadMoreError = result.error.type.userMessage)
                }
            }
        }
    }

    fun toggleFollow(subjectId: String) {
        val state = mutable.value
        if (subjectId in state.followBusy) return
        val subject = state.tabs.getValue(SearchTab.SUBJECTS).items
            .filterIsInstance<SearchResult.SubjectResult>()
            .firstOrNull { it.subject.id == subjectId } ?: return
        val follow = !subject.followed
        mutable.value = state.copy(followBusy = state.followBusy + subjectId)
        scope.launch {
            val result = repository.setFollowed(subjectId, follow)
            val current = mutable.value
            mutable.value = when (result) {
                is ApiResponse.Success -> current.copy(
                    followBusy = current.followBusy - subjectId,
                    tabs = current.tabs + (SearchTab.SUBJECTS to current.tabs.getValue(SearchTab.SUBJECTS).let { tab ->
                        tab.copy(items = tab.items.map { item ->
                            if (item is SearchResult.SubjectResult && item.subject.id == subjectId) item.copy(followed = follow) else item
                        })
                    })
                )
                is ApiResponse.Failure -> current.copy(
                    followBusy = current.followBusy - subjectId,
                    message = result.error.type.userMessage
                )
            }
        }
    }

    fun consumeMessage() {
        mutable.value = mutable.value.copy(message = null)
    }

    private fun applyQuery(text: String, force: Boolean = false) {
        val query = text.trim()
        val state = mutable.value
        if (query == state.query && !force) return
        if (query == state.query && state.tab.error == null && (state.tab.loaded || state.tab.loading)) return
        generation++
        requests.values.forEach { it.cancel() }
        requests.clear()
        mutable.value = state.copy(query = query, tabs = SearchUiState.emptyTabs())
        if (mutable.value.searchable) loadFirst(state.selected)
    }

    private fun loadFirst(tab: SearchTab) {
        requests.remove(tab)?.cancel()
        val gen = generation
        val token = nextToken(tab)
        val query = mutable.value.query
        update(tab) { SearchTabState(loading = true) }
        requests[tab] = scope.launch {
            val result = repository.search(tab, query, 0)
            if (gen != generation || tokens[tab] != token) return@launch
            update(tab) {
                when (result) {
                    is ApiResponse.Success -> SearchTabState(
                        items = result.data.items.distinctBy { item -> item.key },
                        loaded = true,
                        hasMore = result.data.hasMore
                    )
                    is ApiResponse.Failure -> SearchTabState(error = result.error.type.userMessage)
                }
            }
        }
    }

    private fun nextToken(tab: SearchTab): Int = ((tokens[tab] ?: 0) + 1).also { tokens[tab] = it }

    private inline fun update(tab: SearchTab, transform: (SearchTabState) -> SearchTabState) {
        val state = mutable.value
        mutable.value = state.copy(tabs = state.tabs + (tab to transform(state.tabs.getValue(tab))))
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
