
package com.doan.studyhub.admin.ui.users

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.admin.MainActivity
import com.doan.studyhub.admin.databinding.FragmentUsersBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UsersFragment : Fragment() {

    private var _binding: FragmentUsersBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var viewModel: AdminUserViewModel
    private lateinit var userAdapter: AdminUserAdapter

    private var searchJob: Job? = null
    private var isUpdatingSearchText = false

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

        setupRoleTabs()
        setupRecyclerView()
        setupSearch()
        observeViewModel()

        binding.tvUsersError.setOnClickListener {
            viewModel.refreshUsers()
        }

        // Load initial role
        viewModel.loadUsers(refresh = true)
    }

    private fun setupRoleTabs() {
        binding.tabUserRoles.apply {
            addTab(newTab().setText("User"))
            addTab(newTab().setText("Admin"))
            selectTab(getTabAt(0))

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {

                override fun onTabSelected(tab: TabLayout.Tab) {
                    val role = if (tab.position == 1) "admin" else "user"
                    viewModel.selectRole(role)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) {
                    viewModel.refreshUsers()
                }
            })
        }
    }

    private fun setupRecyclerView() {
        userAdapter = AdminUserAdapter(
            onUserClick = { user ->
                // Chỉ mở bài viết đối với tài khoản User
                if (user.role.equals("user", ignoreCase = true)) {
                    val displayName = user.displayName
                        ?: user.username
                        ?: user.email
                        ?: "User"

                    (requireActivity() as? MainActivity)
                        ?.openPostsForUser(user.id, displayName)
                }
            },

            onBanClick = { user ->
                if (user.role.equals("user", ignoreCase = true)) {
                    val name = user.displayName
                        ?: user.username
                        ?: user.email
                        ?: "User"

                    showBanConfirmation(user.id, name)
                }
            },

            onUnbanClick = { user ->
                if (user.role.equals("user", ignoreCase = true)) {
                    val name = user.displayName
                        ?: user.username
                        ?: user.email
                        ?: "User"

                    showUnbanConfirmation(user.id, name)
                }
            }
        )

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

                    if (totalItems > 0 && lastVisibleItem >= totalItems - 5) {
                        viewModel.loadMore()
                    }
                }
            })
        }
    }

    private fun showBanConfirmation(userId: String, userName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Ban người dùng")
            .setMessage("Bạn có chắc muốn ban tài khoản \"$userName\" không?")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Ban") { _, _ ->
                viewModel.banUser(userId)
            }
            .show()
    }

    private fun showUnbanConfirmation(userId: String, userName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Gỡ ban người dùng")
            .setMessage("Bạn có chắc muốn gỡ ban tài khoản \"$userName\" không?")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Gỡ ban") { _, _ ->
                viewModel.unbanUser(userId)
            }
            .show()
    }

    private fun setupSearch() {
        binding.etUserSearch.addTextChangedListener(
            afterTextChanged = { editable ->
                if (isUpdatingSearchText) return@addTextChangedListener

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
                updateUserCount()
                updateEmptyState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedRole.collect {
                updateUserCount()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.pbUsersLoading.visibility =
                    if (loading) View.VISIBLE else View.GONE

                updateEmptyState()
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

                updateEmptyState()
            }
        }

        // Quan sát trạng thái thao tác Ban/Unban
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.actionSuccess.collect { message: String? ->
                if (message != null) {
                    Toast.makeText(
                        requireContext(),
                        message,
                        Toast.LENGTH_SHORT
                    ).show()

                    viewModel.clearActionMessages()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.actionError.collect { message: String? ->
                if (message != null) {
                    Toast.makeText(
                        requireContext(),
                        message,
                        Toast.LENGTH_LONG
                    ).show()

                    viewModel.clearActionMessages()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.actionLoadingUserId.collect { loadingUserId ->
                // Cập nhật nút Ban/Unban của item khi có thao tác
                userAdapter.setLoadingUser(loadingUserId)
            }
        }
    }

    private fun updateUserCount() {
        val roleLabel =
            if (viewModel.selectedRole.value == "admin") "Admin"
            else "User"

        binding.tvUserCount.text =
            "Danh sách $roleLabel (${viewModel.users.value.size})"
    }

    private fun updateEmptyState() {
        binding.tvUsersEmpty.visibility =
            if (viewModel.users.value.isEmpty()
                && !viewModel.isLoading.value
                && viewModel.error.value == null
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        binding.rvUsers.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
