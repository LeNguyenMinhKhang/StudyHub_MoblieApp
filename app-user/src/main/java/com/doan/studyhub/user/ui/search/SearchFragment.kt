package com.doan.studyhub.user.ui.search

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.SearchResult
import com.doan.studyhub.core.data.model.SearchTab
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentSearchBinding
import com.doan.studyhub.user.ui.auth.AuthViewModel
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.post.PostAdapter
import com.doan.studyhub.user.ui.post.PostDetailFragment
import com.doan.studyhub.user.ui.profile.ProfileFragment
import com.doan.studyhub.user.ui.subject.SubjectAdapter
import com.doan.studyhub.user.ui.subject.SubjectDetailFragment
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: SearchViewModel by viewModels()
    private val auth: AuthViewModel by activityViewModels()
    private val postRepository = PostRepository()

    private lateinit var userAdapter: SearchUserAdapter
    private lateinit var postAdapter: PostAdapter
    private lateinit var subjectAdapter: SubjectAdapter
    private lateinit var documentAdapter: SearchDocumentAdapter

    // Last item lists handed to each adapter, so unchanged tabs are not re-bound.
    private val rendered = mutableMapOf<SearchTab, List<SearchResult>>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        rendered.clear()
        setupAdapters()
        binding.rvSearch.layoutManager = LinearLayoutManager(requireContext())
        setupTabs()
        setupInput(firstOpen = state == null && viewModel.uiState.value.query.isEmpty())

        binding.btnSearchBack.setOnClickListener {
            (parentFragment as? Home)?.closeDetail() ?: requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.btnSearchRetry.setOnClickListener { viewModel.retry() }
        binding.btnSearchMoreRetry.setOnClickListener { viewModel.retry() }
        binding.swipeSearch.setColorSchemeResources(R.color.primary, R.color.secondary, R.color.accent)
        binding.swipeSearch.setOnRefreshListener { viewModel.refresh() }
        binding.rvSearch.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) viewModel.loadMore()
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch {
                    auth.uiState.collect { authState ->
                        postAdapter.updateCurrentUserId(authState.profile?.id, authState.profile)
                    }
                }
            }
        }
    }

    private fun setupInput(firstOpen: Boolean) {
        val input = binding.edtSearchQuery
        if (input.text.isNullOrEmpty()) input.setText(viewModel.uiState.value.query)
        input.doAfterTextChanged { viewModel.onQueryChanged(it?.toString().orEmpty()) }
        input.setOnEditorActionListener { view, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            viewModel.submit(view.text?.toString().orEmpty())
            hideKeyboard()
            true
        }
        if (firstOpen) {
            input.requestFocus()
            input.post {
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, 0)
            }
        }
    }

    private fun setupAdapters() {
        userAdapter = SearchUserAdapter(this) { openDetail(ProfileFragment.forUser(it.id)) }
        documentAdapter = SearchDocumentAdapter { openDetail(PostDetailFragment.newInstance(it.postId)) }
        subjectAdapter = SubjectAdapter(
            onSubjectClick = { openDetail(SubjectDetailFragment.newInstance(it.id)) },
            onFollowClick = { viewModel.toggleFollow(it.id) }
        )
        val profile = auth.uiState.value.profile
        postAdapter = PostAdapter(
            fragment = this,
            currentUserId = profile?.id,
            currentUserProfile = profile,
            onPostClick = ::openPost,
            onSubjectClick = { openDetail(SubjectDetailFragment.newInstance(it)) },
            // Owner actions live in the post detail screen; search does not duplicate them.
            onEditClick = ::openPost,
            onDeleteClick = ::openPost,
            onAuthorClick = { openDetail(ProfileFragment.forUser(it)) },
            onLikeClick = { detail, onResult ->
                val postId = detail.post.id
                if (postId.isNullOrBlank()) {
                    onResult(Result.failure(IllegalStateException("Không tìm thấy ID bài viết.")))
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        onResult(runCatching { postRepository.toggleLike(postId) })
                    }
                }
            }
        )
    }

    private fun setupTabs() {
        val tabs = binding.tabsSearch
        val selected = viewModel.uiState.value.selected
        SearchTab.entries.forEach { tab ->
            tabs.addTab(tabs.newTab().setText(tabLabel(tab)).setTag(tab), tab == selected)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                (tab.tag as? SearchTab)?.let(viewModel::selectTab)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun render(state: SearchUiState) {
        val binding = _binding ?: return
        val tab = state.tab
        // Detached adapters keep their data, so switching tabs only re-attaches.
        val adapter = adapterFor(state.selected)
        if (binding.rvSearch.adapter !== adapter) binding.rvSearch.adapter = adapter
        val previous = rendered[state.selected]
        if (previous !== tab.items) {
            rendered[state.selected] = tab.items
            submit(state.selected, previous, tab.items)
        }

        val phase = state.phase
        binding.rvSearch.isVisible = phase == SearchPhase.CONTENT
        binding.layoutSearchStatus.isVisible = phase != SearchPhase.CONTENT
        binding.progressSearch.isVisible = phase == SearchPhase.LOADING
        val status = when (phase) {
            SearchPhase.IDLE -> getString(R.string.search_idle)
            SearchPhase.EMPTY -> getString(R.string.search_empty, state.query)
            SearchPhase.ERROR -> tab.error
            else -> null
        }
        binding.tvSearchStatus.isVisible = status != null
        binding.tvSearchStatus.text = status
        binding.btnSearchRetry.isVisible = phase == SearchPhase.ERROR

        binding.layoutSearchMore.isVisible = tab.loadingMore || tab.loadMoreError != null
        binding.progressSearchMore.isVisible = tab.loadingMore
        binding.tvSearchMoreError.isVisible = tab.loadMoreError != null
        binding.tvSearchMoreError.text = tab.loadMoreError
        binding.btnSearchMoreRetry.isVisible = tab.loadMoreError != null && !tab.loadingMore

        binding.swipeSearch.isEnabled = state.searchable
        if (!tab.loading) binding.swipeSearch.isRefreshing = false

        state.message?.let {
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    private fun submit(tab: SearchTab, previous: List<SearchResult>?, items: List<SearchResult>) {
        when (tab) {
            SearchTab.USERS -> userAdapter.submitList(items.filterIsInstance<SearchResult.User>().map { it.profile })
            SearchTab.POSTS -> {
                val details = items.filterIsInstance<SearchResult.PostResult>().map { it.detail }
                val kept = previous?.size ?: 0
                // Appending keeps like state the adapter already updated for earlier rows.
                if (kept > 0 && items.size > kept && items.subList(0, kept) == previous) {
                    postAdapter.addPosts(details.drop(kept))
                } else {
                    postAdapter.submitList(details)
                }
            }
            SearchTab.SUBJECTS -> items.filterIsInstance<SearchResult.SubjectResult>().let { subjects ->
                subjectAdapter.submitData(subjects.map { it.subject }, subjects.filter { it.followed }.mapTo(HashSet()) { it.subject.id })
            }
            SearchTab.DOCUMENTS -> documentAdapter.submitList(items.filterIsInstance<SearchResult.Document>().map { it.document })
        }
    }

    private fun adapterFor(tab: SearchTab): RecyclerView.Adapter<*> = when (tab) {
        SearchTab.USERS -> userAdapter
        SearchTab.POSTS -> postAdapter
        SearchTab.SUBJECTS -> subjectAdapter
        SearchTab.DOCUMENTS -> documentAdapter
    }

    private fun tabLabel(tab: SearchTab) = when (tab) {
        SearchTab.USERS -> R.string.search_tab_users
        SearchTab.POSTS -> R.string.search_tab_posts
        SearchTab.SUBJECTS -> R.string.search_tab_subjects
        SearchTab.DOCUMENTS -> R.string.search_tab_documents
    }

    private fun openPost(detail: PostDetail) {
        detail.post.id?.let { openDetail(PostDetailFragment.newInstance(it)) }
    }

    private fun openDetail(fragment: Fragment) {
        hideKeyboard()
        (parentFragment as? Home)?.openDetail(fragment)
    }

    private fun hideKeyboard() {
        val input = _binding?.edtSearchQuery ?: return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun onDestroyView() {
        binding.rvSearch.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
