package com.doan.studyhub.user.ui.profile

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentEditProfileBinding
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import kotlinx.coroutines.launch

class EditProfileFragment : Fragment() {
    private var _binding: FragmentEditProfileBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: EditProfileViewModel by viewModels()
    private var completedHandled = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let(viewModel::selectImage)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentEditProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.etUsername.doAfterTextChanged { viewModel.setUsername(it.toString()) }
        binding.etDisplayName.doAfterTextChanged { viewModel.setDisplayName(it.toString()) }
        binding.etBio.doAfterTextChanged { viewModel.setBio(it.toString()) }
        binding.btnChangeAvatar.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnRemoveAvatar.setOnClickListener { viewModel.hideImage() }
        binding.btnSaveProfile.setOnClickListener { viewModel.save() }
        binding.btnCancelEdit.setOnClickListener { (parentFragment as? Home)?.closeDetail() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        viewModel.load(
            state?.getString("draft_username"), state?.getString("draft_name"), state?.getString("draft_bio"),
            state?.getString("draft_avatar_action"), state?.getString("draft_avatar_path")
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val draft = viewModel.uiState.value
        outState.putString("draft_username", draft.username)
        outState.putString("draft_name", draft.displayName)
        outState.putString("draft_bio", draft.bio)
        outState.putString("draft_avatar_action", draft.avatarAction.name)
        outState.putString("draft_avatar_path", draft.avatarDraft?.path)
        super.onSaveInstanceState(outState)
    }

    fun requestLeave(onProceed: () -> Unit) {
        val state = viewModel.uiState.value
        if (!state.isDirty) {
            onProceed()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_discard_title)
            .setMessage(R.string.profile_discard_message)
            .setNegativeButton(R.string.profile_cancel, null)
            .setPositiveButton(R.string.profile_discard) { _, _ ->
                viewModel.discardDraft()
                onProceed()
            }
            .show()
    }

    private fun render(state: EditProfileUiState) {
        binding.progressEditProfile.isVisible = state.isLoading || state.isSaving
        if (binding.etUsername.text?.toString() != state.username) binding.etUsername.setText(state.username)
        if (binding.etDisplayName.text?.toString() != state.displayName) binding.etDisplayName.setText(state.displayName)
        if (binding.etBio.text?.toString() != state.bio) binding.etBio.setText(state.bio)
        binding.tilUsername.error = state.fieldErrors["username"]
        binding.tilDisplayName.error = state.fieldErrors["displayName"]
        binding.tilBio.error = state.fieldErrors["bio"]
        binding.tvEditError.text = state.errorMessage
        binding.tvEditError.isVisible = state.errorMessage != null
        binding.tvEditNotice.isVisible = state.partialSave
        if (state.partialSave) binding.tvEditNotice.setText(R.string.profile_partial_save)
        binding.btnSaveProfile.isEnabled = state.isDirty && !state.isLoading && !state.isSaving
        binding.etUsername.isEnabled = !state.isSaving
        binding.etDisplayName.isEnabled = !state.isSaving
        binding.etBio.isEnabled = !state.isSaving
        binding.btnChangeAvatar.isEnabled = state.original != null && !state.isSaving
        binding.btnRemoveAvatar.isEnabled = state.original != null && !state.isSaving
        AvatarImageLoader.show(this, binding.imgEditAvatar, AvatarSelection(AvatarMode.HIDDEN), null)
        if (state.avatarAction == PendingAvatar.REPLACE && state.avatarDraft?.isFile == true) {
            AvatarImageLoader.show(this, binding.imgEditAvatar, AvatarSelection(AvatarMode.LOCAL, state.avatarDraft), null)
        } else if (state.avatarAction == PendingAvatar.KEEP) {
            state.original?.let { profile ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val selection = viewModel.avatars.selection(profile.id)
                    if (_binding != null && viewModel.uiState.value.avatarAction == PendingAvatar.KEEP) {
                        AvatarImageLoader.show(this@EditProfileFragment, binding.imgEditAvatar, selection, profile.avatarUrl)
                    }
                }
            }
        }
        state.updatedProfile?.let { (parentFragment as? Home)?.onProfileUpdated(it) }
        if (state.completed && !completedHandled) {
            completedHandled = true
            (parentFragment as? Home)?.popDetail()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
