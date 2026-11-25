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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.doan.studyhub.core.data.model.MessageSender
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentConversationChatBinding
import com.doan.studyhub.user.ui.home.Home
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Messenger-style conversation screen shared by direct and group chat: header, grouped bubbles,
 * pill input with icon actions. Subclasses fill the header and start the conversation.
 */
abstract class ConversationChatFragment : Fragment() {

    protected var binding: FragmentConversationChatBinding? = null
        private set

    protected abstract val chatViewModel: ConversationChatViewModel
    protected abstract val isGroup: Boolean
    protected abstract val emptyTextRes: Int

    /** Fills the header once the view exists. */
    protected abstract fun setupHeader(b: FragmentConversationChatBinding)

    /** Starts loading the conversation. */
    protected abstract fun startChat()

    /** Sender shown for received bubbles whose sender profile is not in the state (direct peer). */
    protected open fun fallbackSender(): MessageSender? = null

    /** Extra per-state work for subclasses. */
    protected open fun onState(b: FragmentConversationChatBinding, state: ConversationUiState) = Unit

    protected fun home() = parentFragment as? Home

    private var adapter: MessageAdapter? = null
    private var actionsExpanded = false

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleSelectedImage(it) }
    }

    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleSelectedDocument(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentConversationChatBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = requireNotNull(binding)

        b.btnBack.setOnClickListener {
            val host = home()
            if (host != null) host.closeDetail() else findNavController().navigateUp()
        }
        setupHeader(b)
        setupList(b)
        setupInput(b)
        applyKeyboardInsets(b)
        observeState()
        startChat()
    }

    override fun onResume() {
        super.onResume()
        chatViewModel.refreshReadStatus()
    }

    private fun setupList(b: FragmentConversationChatBinding) {
        adapter = MessageAdapter(
            onImageClick = { openUrl(it) },
            onDocumentClick = { url, _ -> openUrl(url) },
            onRetryClick = { chatViewModel.retrySendMessage(it) }
        )
        b.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        b.rvMessages.adapter = adapter
        b.rvMessages.itemAnimator = null
    }

    private fun setupInput(b: FragmentConversationChatBinding) {
        b.tvEmpty.setText(emptyTextRes)
        b.etMessage.doAfterTextChanged { renderInput(b) }
        b.btnSend.setOnClickListener {
            val text = b.etMessage.text.toString().trim()
            if (text.isNotBlank()) {
                chatViewModel.sendText(text)
                b.etMessage.setText("")
            }
        }
        b.btnExpandActions.setOnClickListener { actionsExpanded = true; renderInput(b) }
        b.btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        b.btnPickDocument.setOnClickListener { pickDocumentLauncher.launch("*/*") }
        renderInput(b)
    }

    /** While typing, image/attach icons collapse into one expand icon to give the field room. */
    private fun renderInput(b: FragmentConversationChatBinding) {
        val hasText = b.etMessage.text.isNotBlank()
        if (!hasText) actionsExpanded = false
        b.layoutActions.isVisible = !hasText || actionsExpanded
        b.btnExpandActions.isVisible = hasText && !actionsExpanded
        b.btnSend.isEnabled = hasText
        b.btnSend.alpha = if (hasText) 1f else 0.4f
    }

    /** Lifts the input bar above the keyboard when the window does not resize itself. */
    private fun applyKeyboardInsets(b: FragmentConversationChatBinding) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.updatePadding(bottom = ime)
            insets
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.uiState.collect { state ->
                    val b = binding ?: return@collect
                    val items = buildChatItems(state.messages, state.currentUserId, isGroup)
                    adapter?.updateSenders(state.senders, fallbackSender())
                    val previousCount = adapter?.itemCount ?: 0
                    adapter?.submitList(items) {
                        if (items.isNotEmpty() && previousCount != items.size) {
                            binding?.rvMessages?.scrollToPosition(items.size - 1)
                        }
                    }
                    b.progressBar.isVisible = state.isLoading
                    b.tvEmpty.isVisible = !state.isLoading && state.conversationId != null && state.messages.isEmpty()
                    onState(b, state)

                    state.errorMessage?.let { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        chatViewModel.clearErrorMessage()
                    }
                }
            }
        }
    }

    private fun handleSelectedImage(uri: Uri) {
        try {
            val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                chatViewModel.sendImage("chat_img_${System.currentTimeMillis()}.jpg", bytes)
            } else {
                toast(getString(R.string.chat_image_cannot_read))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast(getString(R.string.chat_image_error, e.message))
        }
    }

    private fun handleSelectedDocument(uri: Uri) {
        try {
            val resolver = requireContext().contentResolver
            var fileName = "Tai_lieu_${System.currentTimeMillis()}"
            var fileSize = 0L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                }
            }
            if (fileSize > MAX_DOCUMENT_BYTES) {
                Toast.makeText(requireContext(), R.string.chat_file_too_large, Toast.LENGTH_LONG).show()
                return
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                toast(getString(R.string.chat_file_cannot_read))
                return
            }
            chatViewModel.sendDocument(fileName, bytes, formatFileSize(fileSize), resolver.getType(uri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast(getString(R.string.chat_file_error, e.message))
        }
    }

    private fun formatFileSize(size: Long): String = when {
        size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
        size >= 1024 -> String.format(Locale.US, "%.0f KB", size / 1024.0)
        size > 0 -> "$size B"
        else -> "Tệp"
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(getString(R.string.chat_link_cannot_open))
        }
    }

    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        binding?.rvMessages?.adapter = null
        binding = null
        adapter = null
        super.onDestroyView()
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 20 * 1024 * 1024L
    }
}
