
package com.doan.studyhub.user.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.doan.studyhub.core.data.model.NotificationCategory
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentSettingsBinding
import com.doan.studyhub.user.ui.block.BlockedUsersFragment
import com.doan.studyhub.user.ui.home.Home
import kotlinx.coroutines.launch

class Settings : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: NotificationSettingsViewModel by viewModels()

    private val switches
        get() = mapOf(
            NotificationCategory.FRIENDSHIP to binding.switchNotifyFriendship,
            NotificationCategory.LIKE to binding.switchNotifyLike,
            NotificationCategory.COMMENT to binding.switchNotifyComment
        )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)

        // Retry notification settings.
        binding.btnSettingsRetry.setOnClickListener {
            viewModel.load()
        }

        // Open blocked users.
        binding.btnBlockedUsers.setOnClickListener {
            (parentFragment as? Home)?.openDetail(
                BlockedUsersFragment()
            )
        }

        // Open change password screen.
        binding.btnChangePassword.setOnClickListener {
            (parentFragment as? Home)?.openDetail(
                ChangePasswordFragment()
            )
        }

        // Observe notification settings.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }

        viewModel.load()
    }

    private fun render(state: NotificationSettingsUiState) {
        val binding = _binding ?: return
        val confirmed = state.confirmed

        binding.progressSettings.isVisible =
            state.loading && confirmed == null

        switches.forEach { (category, switch) ->
            val pending = state.saving[category]

            switch.isVisible = confirmed != null
            switch.isEnabled = confirmed != null && pending == null

            setCheckedSilently(
                switch,
                category,
                pending ?: confirmed?.isEnabled(category) ?: true
            )
        }

        val status = when {
            state.error != null && confirmed != null ->
                getString(R.string.settings_save_failed)

            state.error != null ->
                state.error

            state.saving.isNotEmpty() ->
                getString(R.string.settings_saving)

            else -> null
        }

        binding.tvSettingsStatus.isVisible = status != null
        binding.tvSettingsStatus.text = status

        binding.btnSettingsRetry.isVisible =
            state.error != null && !state.loading
    }

    private fun setCheckedSilently(
        switch: CompoundButton,
        category: NotificationCategory,
        checked: Boolean
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = checked

        switch.setOnCheckedChangeListener { _, value ->
            viewModel.setEnabled(category, value)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
