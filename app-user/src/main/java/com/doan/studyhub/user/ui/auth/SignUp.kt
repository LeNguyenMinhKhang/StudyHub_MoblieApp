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
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.SignUpBinding
import kotlinx.coroutines.launch

class SignUp : Fragment() {

    private var _binding: SignUpBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SignUpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observeUiState()
    }

    private fun setupListeners() {
        binding.btnSignUp.setOnClickListener {
            val email = binding.etEmail.text?.toString().orEmpty()
            val displayName = binding.etDisplayName.text?.toString().orEmpty()
            val username = binding.etUsername.text?.toString().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()
            val confirmPassword = binding.etConfirmPassword.text?.toString().orEmpty()

            viewModel.signUp(email, password, confirmPassword, username, displayName)
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
                    binding.progressBar.isVisible = state.isLoading || state.isCheckingSession
                    binding.btnSignUp.isEnabled = !state.isLoading && !state.isCheckingSession

                    if (state.errorMessage != null) {
                        binding.tvError.text = state.errorMessage
                        binding.tvError.isVisible = true
                    } else {
                        binding.tvError.isVisible = false
                    }

                    binding.tvSuccess.text = state.successMessage
                    binding.tvSuccess.isVisible = state.successMessage != null

                    if (state.isAuthenticated && findNavController().currentDestination?.id == R.id.signUpFragment) {
                        viewModel.clearMessages()
                        findNavController().navigate(R.id.action_signUp_to_main)
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
