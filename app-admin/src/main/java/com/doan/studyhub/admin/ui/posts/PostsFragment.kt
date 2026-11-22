package com.doan.studyhub.admin.ui.posts

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
import com.doan.studyhub.admin.databinding.FragmentAdminPostsBinding
import com.doan.studyhub.core.data.model.AdminPost
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class PostsFragment : Fragment() {

    private var _binding: FragmentAdminPostsBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var viewModel: AdminPostViewModel
    private lateinit var postAdapter: AdminPostAdapter

    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminPostsBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[AdminPostViewModel::class.java]

        setupRecyclerView()
        setupSearch()
        observeViewModel()

        viewModel.loadPosts(refresh = true)
    }

    private fun setupRecyclerView() {
        postAdapter = AdminPostAdapter(
            onDetailClick = { post ->
                showPostDetail(post)
            },
            onDeleteClick = { post ->
                confirmDeletePost(post)
            }
        )

        binding.rvAdminPosts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = postAdapter

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
        binding.edtSearchPost.addTextChangedListener(
            afterTextChanged = { editable ->
                val query = editable?.toString().orEmpty()

                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(350)
                    viewModel.searchPosts(query)
                }
            }
        )

        binding.edtSearchPost.setOnEditorActionListener {
                _, actionId, _ ->

            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                viewModel.searchPosts(
                    binding.edtSearchPost.text.toString()
                )
                true
            } else {
                false
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.posts.collect { posts ->
                postAdapter.submitList(posts)

                binding.tvPostSummary.text =
                    "Danh sách bài viết (${posts.size})"

                updateEmptyState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressPosts.visibility =
                    if (loading) View.VISIBLE else View.GONE

                updateEmptyState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { errorMessage ->
                if (errorMessage != null) {
                    Toast.makeText(
                        requireContext(),
                        errorMessage,
                        Toast.LENGTH_LONG
                    ).show()
                }

                updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        if (_binding == null) return

        binding.tvEmptyPosts.visibility =
            if (viewModel.posts.value.isEmpty()
                && !viewModel.isLoading.value
                && viewModel.error.value == null
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    // Hiển thị chi tiết bài viết
    // Hiển thị chi tiết bài viết (lấy dữ liệu đầy đủ từ RPC)
    private fun showPostDetail(post: AdminPost) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.progressPosts.visibility = View.VISIBLE

                val detail = viewModel.getPostDetail(post.id)

                if (detail == null) {
                    Toast.makeText(
                        requireContext(),
                        "Không tìm thấy chi tiết bài viết.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val postData = detail["post"]?.let {
                    it as? kotlinx.serialization.json.JsonObject
                }

                val author = detail["author"] as? kotlinx.serialization.json.JsonObject
                val subject = detail["subject"] as? kotlinx.serialization.json.JsonObject
                val files = detail["files"] as? kotlinx.serialization.json.JsonArray
                val comments = detail["comments"] as? kotlinx.serialization.json.JsonArray

                fun value(obj: kotlinx.serialization.json.JsonObject?, key: String): String {
                    return obj?.get(key)
                        ?.let { element ->
                            if (element is kotlinx.serialization.json.JsonNull) null
                            else element.toString().trim('"')
                        } ?: "Không có"
                }

                val authorName = author?.let {
                    value(it, "display_name").takeIf { name -> name != "Không có" }
                        ?: value(it, "username")
                } ?: (post.authorDisplayName ?: post.authorUsername ?: "Không rõ")

                val message = buildString {
                    appendLine("THÔNG TIN BÀI VIẾT")
                    appendLine("━━━━━━━━━━━━━━━━━━━━")
                    appendLine("Tác giả: $authorName")
                    appendLine("Username: ${value(author, "username")}")
                    appendLine("Email: ${value(author, "email")}")
                    appendLine("Môn học: ${value(subject, "name").takeIf { it != "Không có" } ?: (post.subjectName ?: "Không phân loại")}")
                    appendLine("Loại bài: ${value(postData, "type")}")
                    appendLine("Ngày đăng: ${value(postData, "created_at").takeIf { it != "Không có" } ?: post.createdAt}")
                    appendLine()
                    appendLine("TIÊU ĐỀ")
                    appendLine(post.title)
                    appendLine()
                    appendLine("NỘI DUNG")
                    appendLine(post.content ?: "Không có nội dung")
                    appendLine()
                    appendLine("THỐNG KÊ")
                    appendLine("• Lượt thích: ${detail["like_count"] ?: post.likeCount}")
                    appendLine("• Bình luận: ${detail["comment_count"] ?: post.commentCount}")
                    appendLine("• Tệp đính kèm: ${files?.size ?: post.fileCount}")
                    appendLine()

                    appendLine("TỆP ĐÍNH KÈM (${files?.size ?: 0})")
                    appendLine("━━━━━━━━━━━━━━━━━━━━")

                    if (files.isNullOrEmpty()) {
                        appendLine("Không có tệp đính kèm")
                    } else {
                        files.forEachIndexed { index, file ->
                            val fileObj = file as? kotlinx.serialization.json.JsonObject
                            appendLine("${index + 1}. ${value(fileObj, "file_name")}")
                            appendLine("   Loại: ${value(fileObj, "file_type")}")
                            appendLine("   URL: ${value(fileObj, "file_url")}")
                            appendLine()
                        }
                    }

                    appendLine("BÌNH LUẬN (${comments?.size ?: 0})")
                    appendLine("━━━━━━━━━━━━━━━━━━━━")

                    if (comments.isNullOrEmpty()) {
                        appendLine("Chưa có bình luận")
                    } else {
                        comments.take(30).forEachIndexed { index, comment ->
                            val commentObj = comment as? kotlinx.serialization.json.JsonObject
                            appendLine("${index + 1}. ${value(commentObj, "author_display_name").takeIf { it != "Không có" } ?: value(commentObj, "author_username")}")
                            appendLine("   ${value(commentObj, "content")}")
                            appendLine("   Reaction: ${value(commentObj, "reaction_count")}")
                            appendLine("   Ngày: ${value(commentObj, "created_at")}")
                            appendLine()
                        }

                        if (comments.size > 30) {
                            appendLine("... và ${comments.size - 30} bình luận khác")
                        }
                    }
                }

                AlertDialog.Builder(requireContext())
                    .setTitle("Chi tiết bài viết")
                    .setMessage(message)
                    .setPositiveButton("Đóng", null)
                    .show()

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Lỗi tải chi tiết: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (_binding != null) {
                    binding.progressPosts.visibility = View.GONE
                }
            }
        }
    }

    // Xác nhận trước khi xóa
    private fun confirmDeletePost(post: AdminPost) {
        AlertDialog.Builder(requireContext())
            .setTitle("Xóa bài viết")
            .setMessage(
                "Bạn có chắc chắn muốn xóa bài viết \"${post.title}\" không?\n\n" +
                        "Các bình luận, lượt thích và thông tin tệp liên quan trong cơ sở dữ liệu " +
                        "cũng sẽ bị xóa theo."
            )
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa") { _, _ ->
                viewModel.deletePost(post.id)
            }
            .show()
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        binding.rvAdminPosts.adapter = null
        _binding = null
        super.onDestroyView()
    }
}