package com.doan.studyhub.user.ui.chat

import android.os.Bundle
import androidx.fragment.app.viewModels
import com.bumptech.glide.Glide
import com.doan.studyhub.core.data.model.MessageSender
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentConversationChatBinding

/** One-to-one chat; layout and behaviour come from [ConversationChatFragment]. */
class DirectChatFragment : ConversationChatFragment() {

    private val viewModel: DirectChatViewModel by viewModels()
    override val chatViewModel: ConversationChatViewModel get() = viewModel
    override val isGroup = false
    override val emptyTextRes = R.string.chat_direct_empty

    private var conversationId: String? = null
    private var targetUserId: String? = null
    private var targetName: String? = null
    private var targetAvatar: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            conversationId = it.getString(ARG_CONVERSATION_ID)
            targetUserId = it.getString(ARG_TARGET_USER_ID)
            targetName = it.getString(ARG_TARGET_NAME)
            targetAvatar = it.getString(ARG_TARGET_AVATAR)
        }
    }

    override fun setupHeader(b: FragmentConversationChatBinding) {
        b.tvHeaderName.text = targetName ?: getString(R.string.chat_default_title)
        b.tvHeaderStatus.text = getString(R.string.chat_peer_subtitle)
        val initials = InitialsAvatarDrawable(targetName ?: "")
        Glide.with(this).load(targetAvatar).circleCrop().placeholder(initials).error(initials).into(b.ivHeaderAvatar)
    }

    override fun fallbackSender() = MessageSender(targetUserId.orEmpty(), targetName.orEmpty(), targetAvatar)

    override fun startChat() = viewModel.initChat(conversationId, targetUserId)

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
