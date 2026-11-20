package com.doan.studyhub.user.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.doan.studyhub.user.databinding.FragmentChangePasswordBinding
import kotlinx.coroutines.launch

class ChangePasswordFragment : Fragment() {

    private var _binding: FragmentChangePasswordBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: SettingsPasswordViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View {
        _binding = FragmentChangePasswordBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)

        setupListeners()
        observeState()
    }

    private fun setupListeners() {
        binding.btnBackChangePassword.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnSubmitChangePassword.setOnClickListener {
            viewModel.updatePassword(
                currentPassword = binding.edtCurrentPassword.text
                    ?.toString().orEmpty(),
                newPassword = binding.edtNewPassword.text
                    ?.toString().orEmpty(),
                confirmation = binding.edtConfirmPassword.text
                    ?.toString().orEmpty()
            )
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->

                    binding.progressChangePassword.isVisible =
                        state.isUpdating

                    binding.btnSubmitChangePassword.isEnabled =
                        !state.isUpdating

                    binding.btnBackChangePassword.isEnabled =
                        !state.isUpdating

                    binding.tvChangePasswordError.isVisible =
                        state.error != null

                    binding.tvChangePasswordError.text =
                        state.error

                    binding.tvChangePasswordSuccess.isVisible =
                        state.success != null

                    binding.tvChangePasswordSuccess.text =
                        state.success

                    if (state.success != null) {
                        binding.edtCurrentPassword.text?.clear()
                        binding.edtNewPassword.text?.clear()
                        binding.edtConfirmPassword.text?.clear()

                        // Quay lại Cài đặt sau khi cập nhật thành công.
                        binding.root.postDelayed({
                            if (isAdded && _binding != null) {
                                parentFragmentManager.popBackStack()
                            }
                        }, 1200)

                        viewModel.clearMessages()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
