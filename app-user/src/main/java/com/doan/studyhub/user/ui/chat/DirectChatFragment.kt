package com.doan.studyhub.user.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentDirectChatBinding
import com.doan.studyhub.user.ui.home.Home
import kotlinx.coroutines.launch
import java.util.Locale

class DirectChatFragment : Fragment() {

    private var _binding: FragmentDirectChatBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: DirectChatViewModel by viewModels()
    private var messageAdapter: MessageAdapter? = null

    private var conversationId: String? = null
    private var targetUserId: String? = null
    private var targetName: String? = null
    private var targetAvatar: String? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleSelectedImage(it) }
    }

    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleSelectedDocument(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            conversationId = it.getString(ARG_CONVERSATION_ID)
            targetUserId = it.getString(ARG_TARGET_USER_ID)
            targetName = it.getString(ARG_TARGET_NAME)
            targetAvatar = it.getString(ARG_TARGET_AVATAR)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDirectChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupInputListeners()
        observeState()

        viewModel.initChat(conversationId, targetUserId)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshReadStatus()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            val home = parentFragment as? Home
            if (home != null) {
                home.closeDetail()
            } else {
                findNavController().navigateUp()
            }
        }

        binding.tvHeaderName.text = targetName ?: "Trò chuyện"

        Glide.with(this)
            .load(targetAvatar)
            .circleCrop()
            .placeholder(R.drawable.ic_person)
            .error(R.drawable.ic_person)
            .into(binding.ivHeaderAvatar)
    }

    private fun setupRecyclerView() {
        val currentUserId = viewModel.uiState.value.currentUserId ?: ""
        messageAdapter = MessageAdapter(
            currentUserId = currentUserId,
            onImageClick = { imageUrl ->
                openUrlInBrowserOrViewer(imageUrl)
            },
            onDocumentClick = { fileUrl, _ ->
                openUrlInBrowserOrViewer(fileUrl)
            },
            onRetryClick = { failedMsg ->
                viewModel.retrySendMessage(failedMsg)
            }
        )

        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = messageAdapter
    }

    private fun setupInputListeners() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotBlank()) {
                viewModel.sendText(text)
                binding.etMessage.setText("")
            }
        }

        binding.btnPickImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnPickDocument.setOnClickListener {
            pickDocumentLauncher.launch("*/*")
        }
    }

    private fun handleSelectedImage(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bytes = inputStream?.use { it.readBytes() }

            if (bytes != null && bytes.isNotEmpty()) {
                val fileName = "chat_img_${System.currentTimeMillis()}.jpg"
                viewModel.sendImage(fileName, bytes)
            } else {
                Toast.makeText(requireContext(), "Không thể đọc dữ liệu ảnh", Toast.LENGTH_SHORT).show()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Lỗi khi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedDocument(uri: Uri) {
        try {
            val contentResolver = requireContext().contentResolver
            var fileName = "Tai_lieu_${System.currentTimeMillis()}"
            var fileSize = 0L

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                }
            }

            // Giới hạn 20MB
            val maxSizeBytes = 20 * 1024 * 1024L
            if (fileSize > maxSizeBytes) {
                Toast.makeText(requireContext(), "Dung lượng tệp vượt quá 20MB. Vui lòng chọn tệp nhỏ hơn!", Toast.LENGTH_LONG).show()
                return
            }

            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(requireContext(), "Không thể đọc dữ liệu tệp", Toast.LENGTH_SHORT).show()
                return
            }

            val formattedSize = formatFileSize(fileSize)
            val mimeType = contentResolver.getType(uri)

            viewModel.sendDocument(fileName, bytes, formattedSize, mimeType)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Lỗi khi xử lý tài liệu: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format(Locale.US, "%.0f KB", size / 1024.0)
            size > 0 -> "$size B"
            else -> "Tệp"
        }
    }

    private fun openUrlInBrowserOrViewer(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Không thể mở liên kết này", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.isVisible = state.isLoading
                    binding.progressBarSending.isVisible = state.isSending
                    binding.btnSend.isVisible = !state.isSending

                    if (messageAdapter != null) {
                        val previousCount = messageAdapter?.itemCount ?: 0
                        messageAdapter?.submitList(state.messages) {
                            if (state.messages.isNotEmpty()) {
                                if (previousCount != state.messages.size) {
                                    binding.rvMessages.scrollToPosition(state.messages.size - 1)
                                }
                            }
                        }
                    }

                    state.errorMessage?.let { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        viewModel.clearErrorMessage()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        messageAdapter = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_CONVERSATION_ID = "conversation_id"
        const val ARG_TARGET_USER_ID = "target_user_id"
        const val ARG_TARGET_NAME = "target_name"
        const val ARG_TARGET_AVATAR = "target_avatar"

        fun newInstance(
            conversationId: String? = null,
            targetUserId: String? = null,
            targetName: String? = null,
            targetAvatar: String? = null
        ): DirectChatFragment {
            return DirectChatFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONVERSATION_ID, conversationId)
                    putString(ARG_TARGET_USER_ID, targetUserId)
                    putString(ARG_TARGET_NAME, targetName)
                    putString(ARG_TARGET_AVATAR, targetAvatar)
                }
            }
        }
    }
}
