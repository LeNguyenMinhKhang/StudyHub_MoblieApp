package com.doan.studyhub.user.ui.profile

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.repository.ProfileRepository
import com.doan.studyhub.user.R
import kotlinx.coroutines.launch

class ProfileLookupDialogFragment : DialogFragment() {
    private val repository = ProfileRepository()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.profile_lookup_hint)
            isSingleLine = true
            setPadding(36, 16, 36, 16)
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
        }
        val progress = ProgressBar(requireContext()).apply { isVisible = false }
        content.addView(progress)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_lookup_title)
            .setView(content)
            .setPositiveButton(R.string.profile_lookup_action, null)
            .setNegativeButton(R.string.profile_cancel, null)
            .create()
        dialog.setOnShowListener {
            val search = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            search.setOnClickListener {
                val username = input.text.toString().trim()
                if (username.isEmpty()) {
                    input.error = getString(R.string.profile_lookup_hint)
                    return@setOnClickListener
                }
                search.isEnabled = false
                progress.isVisible = true
                lifecycleScope.launch {
                    val response = repository.findByUsername(username)
                    if (!isAdded) return@launch
                    when (response) {
                        is ApiResponse.Success -> {
                            val profile = response.data
                            if (profile == null) input.error = getString(R.string.profile_unavailable)
                            else {
                                setFragmentResult(RESULT_KEY, bundleOf(RESULT_PROFILE_ID to profile.id))
                                dismiss()
                            }
                        }
                        is ApiResponse.Failure -> input.error = response.error.message
                    }
                    search.isEnabled = true
                    progress.isVisible = false
                }
            }
        }
        return dialog
    }

    companion object {
        const val RESULT_KEY = "profile_lookup"
        const val RESULT_PROFILE_ID = "profile_id"
    }
}
