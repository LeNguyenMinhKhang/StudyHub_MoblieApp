package com.doan.studyhub.user.ui.post

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.core.data.model.Subject
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.core.data.repository.SubjectRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentPostCreateBinding
import com.doan.studyhub.user.ui.auth.AuthViewModel
import com.doan.studyhub.user.ui.profile.AvatarImageLoader
import kotlinx.coroutines.launch

class PostCreateFragment : Fragment() {

    private var _binding: FragmentPostCreateBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val authViewModel: AuthViewModel by activityViewModels()
    private val postRepository = PostRepository()
    private val subjectRepository = SubjectRepository()
    private val authRepository = AuthRepository()

    private var subjectsList = listOf<Subject>()
    private var selectedSubject: Subject? = null
    private var selectedPostType: String = "none"
    private var selectedVisibility: String = com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PUBLIC
    private val selectedFiles = mutableListOf<SelectedFile>()
    private lateinit var fileAdapter: AttachedFileAdapter

    private var editPostId: String? = null

    private val selectFilesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        handleSelectedUris(uris)
    }

    private fun handleSelectedUris(uris: List<Uri>) {
        uris.forEach { uri ->
            val fileInfo = getUriFileInfo(uri)
            val mimeType = requireContext().contentResolver.getType(uri)
                ?: getMimeTypeFromExtension(fileInfo.first)
            selectedFiles.add(
                SelectedFile(
                    uri = uri,
                    name = fileInfo.first,
                    size = fileInfo.second,
                    type = mimeType
                )
            )
        }
        updateFileList()
    }

    companion object {
        private const val ARG_POST_ID = "edit_post_id"

        fun newInstance(editPostId: String? = null) = PostCreateFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_POST_ID, editPostId)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPostCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editPostId = arguments?.getString(ARG_POST_ID)

        loadAuthorInfo()
        setPostTypeSelection(null, null)
        setVisibilitySelection(com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PUBLIC)

        binding.tvCreatePostTitle.text = if (editPostId != null) "Chỉnh sửa bài viết" else "Tạo bài viết"
        binding.btnSubmitPost.text = if (editPostId != null) "Lưu" else "Đăng"

        fileAdapter = AttachedFileAdapter { fileToRemove ->
            selectedFiles.remove(fileToRemove)
            updateFileList()
            if (fileToRemove.remoteFileId != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        postRepository.deletePostFile(fileToRemove.remoteFileId)
                    } catch (e: Exception) {
                        // ignored
                    }
                }
            }
        }

        binding.rvAttachedFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAttachedFiles.adapter = fileAdapter

        binding.btnBackCreatePost.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.tvSelectSubjectBadge.setOnClickListener { showSubjectSearchDialog() }
        binding.btnPickSubject.setOnClickListener { showSubjectSearchDialog() }

        binding.tvSelectTypeBadge.setOnClickListener { showPostTypeDialog() }
        binding.btnPickType.setOnClickListener { showPostTypeDialog() }

        binding.layoutSelectVisibilityBadge.setOnClickListener { showVisibilityDialog() }
        binding.btnPickVisibility.setOnClickListener { showVisibilityDialog() }

        binding.btnPickImage.setOnClickListener {
            selectFilesLauncher.launch("image/*")
        }

        binding.btnPickDocument.setOnClickListener {
            selectFilesLauncher.launch("*/*")
        }

        binding.btnRemovePreviewImage.setOnClickListener {
            val previewFile = selectedFiles.firstOrNull { isImageFile(it) }
            if (previewFile != null) {
                selectedFiles.remove(previewFile)
                updateFileList()
            }
        }

        binding.btnSubmitPost.setOnClickListener {
            submitPost()
        }

        loadSubjectsAndData()
    }

    private fun loadAuthorInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            authViewModel.uiState.collect { state ->
                val profile = state.profile
                val name = when {
                    !profile?.displayName.isNullOrBlank() -> profile.displayName
                    !profile?.username.isNullOrBlank() -> profile.username
                    else -> "Người dùng"
                }
                binding.tvCreateAuthorName.text = name

                AvatarImageLoader.show(
                    fragment = this@PostCreateFragment,
                    view = binding.imgCreateAuthorAvatar,
                    selection = AvatarSelection(AvatarMode.REMOTE),
                    remoteUrl = profile?.avatarUrl
                )
            }
        }
    }

    private fun updateFileList() {
        val nonImageFiles = selectedFiles.filter { !isImageFile(it) }
        fileAdapter.submitList(nonImageFiles)
        binding.rvAttachedFiles.isVisible = nonImageFiles.isNotEmpty()
        updateImagePreview()
    }

    private fun updateImagePreview() {
        val imageFile = selectedFiles.firstOrNull { isImageFile(it) }
        if (imageFile != null) {
            binding.cardImagePreviewContainer.isVisible = true
            val imageSource: Any? = imageFile.uri ?: imageFile.remoteUrl
            if (imageSource != null) {
                Glide.with(this)
                    .load(imageSource)
                    .into(binding.imgCreateFilePreview)
            }
        } else {
            binding.cardImagePreviewContainer.isVisible = false
        }
    }

    private fun isImageFile(file: SelectedFile): Boolean {
        return file.type?.startsWith("image/") == true ||
                file.name.endsWith(".png", true) ||
                file.name.endsWith(".jpg", true) ||
                file.name.endsWith(".jpeg", true) ||
                file.name.endsWith(".webp", true)
    }

    private fun loadSubjectsAndData() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                subjectsList = subjectRepository.getActiveSubjects()

                // If editing, load post details
                if (editPostId != null) {
                    val detail = postRepository.getPostDetail(editPostId!!)
                    if (detail != null) {
                        binding.edtPostTitle.setText(detail.post.title)
                        binding.edtPostContent.setText(detail.post.content)

                        val (category, visibility) = com.doan.studyhub.core.data.model.PostTypeUtils.parse(detail.post.type)
                        val typeName = when (category) {
                            "question" -> "Hỏi đáp"
                            "document" -> "Tài liệu"
                            "knowledge" -> "Kiến thức"
                            else -> null
                        }
                        if (typeName != null) {
                            setPostTypeSelection(category, typeName)
                        } else {
                            setPostTypeSelection(null, null)
                        }
                        setVisibilitySelection(visibility)

                        val foundSubject = subjectsList.find { it.id == detail.post.subjectId }
                        if (foundSubject != null && !foundSubject.name.contains("Chung", ignoreCase = true)) {
                            setSubjectSelection(foundSubject)
                        } else {
                            setSubjectSelection(null)
                        }

                        // Existing files
                        detail.files.forEach { file ->
                            selectedFiles.add(
                                SelectedFile(
                                    name = file.fileName,
                                    size = file.fileSize ?: 0,
                                    type = file.fileType,
                                    remoteUrl = file.fileUrl,
                                    remoteFileId = file.id
                                )
                            )
                        }
                        updateFileList()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Không thể tải môn học: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showPostTypeDialog() {
        val types = arrayOf("Không phân loại", "Hỏi đáp", "Tài liệu", "Kiến thức")
        val keys = arrayOf("none", "question", "document", "knowledge")

        AlertDialog.Builder(requireContext())
            .setTitle("Chọn loại bài viết")
            .setItems(types) { _, which ->
                val key = keys[which]
                if (key == "none") {
                    setPostTypeSelection("none", null)
                } else {
                    setPostTypeSelection(key, types[which])
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showVisibilityDialog() {
        val options = arrayOf(
            "🌐 Công khai (Tất cả mọi người)",
            "👥 Bạn bè (Chỉ bạn bè trên StudyHub)",
            "🔒 Chỉ mình tôi (Riêng tư)"
        )
        val keys = arrayOf(
            com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PUBLIC,
            com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_FRIENDS,
            com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PRIVATE
        )
        val currentIndex = keys.indexOf(selectedVisibility).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Ai có thể xem bài viết này?")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                setVisibilitySelection(keys[which])
                dialog.dismiss()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun setVisibilitySelection(visibilityKey: String) {
        selectedVisibility = visibilityKey
        when (visibilityKey) {
            com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PRIVATE -> {
                binding.imgSelectedVisibilityIcon.setImageResource(R.drawable.ic_lock)
                binding.btnPickVisibility.setImageResource(R.drawable.ic_lock)
                binding.tvSelectVisibilityText.text = "Chỉ mình tôi"
            }
            com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_FRIENDS -> {
                binding.imgSelectedVisibilityIcon.setImageResource(R.drawable.ic_group)
                binding.btnPickVisibility.setImageResource(R.drawable.ic_group)
                binding.tvSelectVisibilityText.text = "Bạn bè"
            }
            else -> {
                binding.imgSelectedVisibilityIcon.setImageResource(R.drawable.ic_public)
                binding.btnPickVisibility.setImageResource(R.drawable.ic_public)
                binding.tvSelectVisibilityText.text = "Công khai"
            }
        }
    }

    private fun setPostTypeSelection(typeKey: String?, typeName: String?) {
        if (!typeKey.isNullOrBlank() && typeKey != "none") {
            selectedPostType = typeKey
            binding.tvSelectTypeBadge.text = "📌 Loại: $typeName"
        } else {
            selectedPostType = "none"
            binding.tvSelectTypeBadge.text = "📌 Chọn loại bài"
        }
    }

    private fun showSubjectSearchDialog() {
        val context = requireContext()
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Chọn môn học liên quan")

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 12)
        }

        val searchEdit = EditText(context).apply {
            hint = "Tìm kiếm môn học..."
            isSingleLine = true
            setPadding(24, 24, 24, 24)
            setBackgroundResource(android.R.drawable.edit_text)
        }
        container.addView(searchEdit)

        val listView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                600
            )
        }
        container.addView(listView)

        var filteredSubjects = subjectsList

        fun renderSubjects(list: List<Subject>) {
            val names = mutableListOf("❌ Không chọn môn học")
            names.addAll(list.map { it.name })
            listView.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, names)
        }

        renderSubjects(filteredSubjects)

        builder.setView(container)
        builder.setNegativeButton("Đóng", null)

        val dialog = builder.create()

        searchEdit.doAfterTextChanged { text ->
            val query = text.toString().trim()
            filteredSubjects = subjectsList.filter { it.name.contains(query, ignoreCase = true) }
            renderSubjects(filteredSubjects)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                setSubjectSelection(null)
            } else if (position - 1 in filteredSubjects.indices) {
                setSubjectSelection(filteredSubjects[position - 1])
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setSubjectSelection(subject: Subject?) {
        selectedSubject = subject
        if (subject != null) {
            binding.tvSelectSubjectBadge.text = "📚 Môn: ${subject.name}"
        } else {
            binding.tvSelectSubjectBadge.text = "📚 Thêm môn học"
        }
    }

    private fun submitPost() {
        val title = binding.edtPostTitle.text.toString().trim()
        val content = binding.edtPostContent.text.toString().trim()

        if (title.isBlank()) {
            Toast.makeText(requireContext(), "Vui lòng nhập tiêu đề bài viết", Toast.LENGTH_SHORT).show()
            return
        }

        // If user didn't choose a subject, do NOT auto-fill anything (keep null)
        val targetSubjectId = selectedSubject?.id

        // Format combined category and visibility into type column
        val finalPostType = com.doan.studyhub.core.data.model.PostTypeUtils.format(selectedPostType, selectedVisibility)

        viewLifecycleOwner.lifecycleScope.launch {
            val userId = authRepository.getCurrentUserId()
            if (userId.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Bạn cần đăng nhập để đăng bài", Toast.LENGTH_SHORT).show()
                return@launch
            }

            binding.layoutLoading.isVisible = true
            try {
                val targetPostId: String = if (editPostId != null) {
                    postRepository.updatePost(
                        postId = editPostId!!,
                        title = title,
                        content = content,
                        type = finalPostType,
                        subjectId = targetSubjectId
                    )
                    editPostId!!
                } else {
                    val created = postRepository.createPost(
                        authorId = userId,
                        subjectId = targetSubjectId,
                        type = finalPostType,
                        title = title,
                        content = content
                    )
                    created.id ?: error("Tạo bài viết thất bại")
                }

                // Upload new local files
                val localFiles = selectedFiles.filter { it.uri != null }
                var uploadErrors = 0
                localFiles.forEachIndexed { index, selectedFile ->
                    binding.tvLoadingText.text = "Đang tải tệp ${index + 1}/${localFiles.size}..."
                    val uri = selectedFile.uri!!
                    val bytes = try {
                        requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        null
                    }
                    if (bytes != null) {
                        try {
                            postRepository.uploadPostFile(
                                postId = targetPostId,
                                fileName = selectedFile.name,
                                fileBytes = bytes,
                                fileType = selectedFile.type
                            )
                        } catch (e: Exception) {
                            uploadErrors++
                            android.util.Log.e("PostCreateFragment", "Upload file failed: ${selectedFile.name}", e)
                        }
                    } else {
                        uploadErrors++
                    }
                }

                val successMsg = when {
                    editPostId != null -> "Đã cập nhật bài viết!"
                    uploadErrors > 0 -> "Đã đăng bài viết (có $uploadErrors tệp tải lên thất bại)"
                    else -> "Đăng bài viết thành công!"
                }
                Toast.makeText(requireContext(), successMsg, Toast.LENGTH_SHORT).show()

                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Thao tác thất bại: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                _binding?.layoutLoading?.isVisible = false
            }
        }
    }

    private fun getMimeTypeFromExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun getUriFileInfo(uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return Pair(name, size)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
