package com.doan.studyhub.user.ui.group

import android.os.Bundle
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.doan.studyhub.core.data.model.CallMode
import com.doan.studyhub.core.data.model.CallStatus
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentConversationChatBinding
import com.doan.studyhub.user.ui.chat.ConversationChatFragment
import com.doan.studyhub.user.ui.chat.ConversationChatViewModel
import com.doan.studyhub.user.ui.chat.ConversationUiState
import com.doan.studyhub.user.ui.chat.GroupChatViewModel
import com.doan.studyhub.user.ui.chat.InitialsAvatarDrawable
import com.doan.studyhub.user.ui.group.call.CallSessionManager
import com.doan.studyhub.user.ui.group.call.GroupCallBannerViewModel
import com.doan.studyhub.user.ui.group.call.GroupCallFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Chat of a study group: header with call/info icons, ongoing-call bar and the shared message list. */
class GroupChatFragment : ConversationChatFragment() {

    private val viewModel: GroupChatViewModel by viewModels()
    private val callModel: GroupCallBannerViewModel by viewModels()
    override val chatViewModel: ConversationChatViewModel get() = viewModel
    override val isGroup = true
    override val emptyTextRes = R.string.chat_group_empty

    private val groupId get() = requireNotNull(requireArguments().getString(ARG_GROUP_ID))
    private val initialName get() = requireArguments().getString(ARG_GROUP_NAME)

    /** Guards against opening the call screen twice from rapid taps. */
    private var openingCall = false
    private var closing = false
    private var renderedTitle: String? = null

    override fun setupHeader(b: FragmentConversationChatBinding) {
        closing = false
        renderTitle(b, initialName.orEmpty(), null)
        b.btnInfo.isVisible = true
        b.btnCallAudio.isVisible = true
        b.btnCallVideo.isVisible = true
        b.btnInfo.setOnClickListener { home()?.openDetail(GroupDetailFragment.newInstance(groupId)) }
        bindCallBar(b)
    }

    override fun startChat() = viewModel.initGroup(groupId)

    override fun onState(b: FragmentConversationChatBinding, state: ConversationUiState) {
        state.header?.let { renderTitle(b, it.title, it.memberCount) }
        if (state.inaccessible && !closing) {
            closing = true
            Toast.makeText(requireContext(), R.string.group_closed, Toast.LENGTH_LONG).show()
            home()?.closeGroupDetails()
        }
    }

    private fun renderTitle(b: FragmentConversationChatBinding, title: String, memberCount: Int?) {
        b.tvHeaderName.text = title.ifBlank { getString(R.string.chat_default_title) }
        b.tvHeaderStatus.text = memberCount?.let { getString(R.string.chat_members_count, it) }.orEmpty()
        if (renderedTitle != title) {
            renderedTitle = title
            b.ivHeaderAvatar.setImageDrawable(InitialsAvatarDrawable(title))
            b.ivHeaderAvatar.contentDescription = getString(R.string.chat_avatar_of, title)
        }
    }

    /** Banner state is refreshed every POLL_MS only while this screen is STARTED; no background ringing. */
    private fun bindCallBar(b: FragmentConversationChatBinding) {
        val session = CallSessionManager.get(requireContext())
        fun open(mode: CallMode) {
            if (openingCall) return
            openingCall = true
            home()?.openDetail(GroupCallFragment.newInstance(groupId, mode))
        }
        b.btnCallAudio.setOnClickListener { open(CallMode.AUDIO) }
        b.btnCallVideo.setOnClickListener { open(CallMode.VIDEO) }
        b.btnCallAction.setOnClickListener {
            open(if (session.state.value.active) session.state.value.call?.initialMode ?: CallMode.AUDIO else CallMode.AUDIO)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { while (true) { callModel.refresh(groupId); delay(GroupCallBannerViewModel.POLL_MS) } }
                combine(callModel.state, session.state) { banner, local -> banner to local }.collect { (banner, local) ->
                    val current = binding ?: return@collect
                    val mine = local.active && local.groupId == groupId
                    val otherCall = local.active && local.groupId != groupId
                    val call = banner.call
                    val openCall = call != null && call.status != CallStatus.ENDED && call.status != CallStatus.FAILED
                    val canEnter = !otherCall && !openingCall && (call == null || GroupCallBannerViewModel.joinable(call))

                    current.callBar.isVisible = mine || openCall
                    current.btnCallAction.setText(if (mine) R.string.chat_call_bar_return else R.string.chat_call_bar_join)
                    current.btnCallAction.isEnabled = mine || canEnter
                    current.tvCallStatus.text = when {
                        mine -> getString(R.string.call_banner_mine)
                        call == null -> ""
                        call.status == CallStatus.ENDING -> getString(R.string.call_banner_ending)
                        !GroupCallBannerViewModel.joinable(call) -> getString(R.string.call_banner_full, call.capacity)
                        else -> getString(R.string.call_banner_active, call.participantCount, call.capacity, call.startedBy.displayName)
                    }

                    val enabled = mine || canEnter
                    current.btnCallAudio.isEnabled = enabled
                    current.btnCallVideo.isEnabled = enabled
                    current.btnCallAudio.alpha = if (enabled) 1f else 0.4f
                    current.btnCallVideo.alpha = if (enabled) 1f else 0.4f
                    val joining = mine || openCall
                    current.btnCallAudio.contentDescription = when {
                        otherCall -> getString(R.string.call_other_group)
                        joining -> getString(R.string.chat_call_audio_join)
                        else -> getString(R.string.chat_call_audio)
                    }
                    current.btnCallVideo.contentDescription = when {
                        otherCall -> getString(R.string.call_other_group)
                        joining -> getString(R.string.chat_call_video_join)
                        else -> getString(R.string.chat_call_video)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        openingCall = false
    }

    override fun onDestroyView() {
        renderedTitle = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_GROUP_ID = "group_id"
        const val ARG_GROUP_NAME = "group_name"

        fun newInstance(groupId: String, groupName: String? = null) = GroupChatFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_GROUP_ID, groupId)
                putString(ARG_GROUP_NAME, groupName)
            }
        }
    }
}
