package com.doan.studyhub.user.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.doan.studyhub.user.databinding.ForgotPasswordBinding
import kotlinx.coroutines.launch

class ForgotPassword : Fragment() {

    private var _binding: ForgotPasswordBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ForgotPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observeUiState()
    }

    private fun setupListeners() {
        binding.btnResetPassword.setOnClickListener {
            val email = binding.etEmail.text?.toString().orEmpty()
            viewModel.sendPasswordResetEmail(email)
        }

        binding.btnNavigateToSignIn.setOnClickListener {
            viewModel.clearMessages()
            findNavController().popBackStack()
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.isVisible = state.isLoading
                    binding.btnResetPassword.isEnabled = !state.isLoading

                    if (state.errorMessage != null) {
                        binding.tvError.text = state.errorMessage
                        binding.tvError.isVisible = true
                    } else {
                        binding.tvError.isVisible = false
                    }

                    if (state.successMessage != null) {
                        binding.tvSuccess.text = state.successMessage
                        binding.tvSuccess.isVisible = true
                    } else {
                        binding.tvSuccess.isVisible = false
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
