package com.doan.studyhub.admin.ui.users

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.admin.databinding.FragmentUsersBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UsersFragment : Fragment() {

    private var _binding: FragmentUsersBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var viewModel: AdminUserViewModel
    private lateinit var userAdapter: AdminUserAdapter

    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUsersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[AdminUserViewModel::class.java]

        setupRecyclerView()
        setupSearch()
        observeViewModel()

        binding.tvUsersError.setOnClickListener {
            viewModel.refreshUsers()
        }

        // Tải danh sách lần đầu
        viewModel.loadUsers(refresh = true)
    }

    private fun setupRecyclerView() {
        userAdapter = AdminUserAdapter()

        binding.rvUsers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = userAdapter

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int
                ) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dy <= 0) return

                    val layoutManager =
                        recyclerView.layoutManager as? LinearLayoutManager
                            ?: return

                    val totalItems = layoutManager.itemCount
                    val lastVisibleItem =
                        layoutManager.findLastVisibleItemPosition()

                    if (totalItems > 0 &&
                        lastVisibleItem >= totalItems - 5
                    ) {
                        viewModel.loadMore()
                    }
                }
            })
        }
    }

    private fun setupSearch() {
        binding.etUserSearch.addTextChangedListener(
            afterTextChanged = { editable ->
                val query = editable?.toString().orEmpty()

                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(350)
                    viewModel.searchUsers(query)
                }
            }
        )

        binding.etUserSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                viewModel.searchUsers(
                    binding.etUserSearch.text.toString()
                )
                true
            } else {
                false
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.users.collect { users ->
                userAdapter.submitList(users)

                binding.tvUserCount.text =
                    "Danh sách tài khoản (${users.size})"

                binding.tvUsersEmpty.visibility =
                    if (users.isEmpty() && !viewModel.isLoading.value
                        && viewModel.error.value == null
                    ) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.pbUsersLoading.visibility =
                    if (loading) View.VISIBLE else View.GONE

                binding.tvUsersEmpty.visibility =
                    if (viewModel.users.value.isEmpty()
                        && !loading
                        && viewModel.error.value == null
                    ) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { errorMessage ->
                binding.tvUsersError.visibility =
                    if (errorMessage != null) View.VISIBLE else View.GONE

                binding.tvUsersError.text =
                    errorMessage?.let {
                        "$it\n\nNhấn vào đây để thử lại"
                    } ?: ""

                binding.tvUsersEmpty.visibility =
                    if (viewModel.users.value.isEmpty()
                        && !viewModel.isLoading.value
                        && errorMessage == null
                    ) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
        }
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        binding.rvUsers.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
