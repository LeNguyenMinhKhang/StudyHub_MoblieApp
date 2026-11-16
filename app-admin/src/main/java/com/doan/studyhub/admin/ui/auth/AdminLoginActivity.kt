package com.doan.studyhub.admin.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.doan.studyhub.admin.MainActivity
import com.doan.studyhub.admin.databinding.ActivityAdminLoginBinding
import kotlinx.coroutines.launch

class AdminLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminLoginBinding
    private val viewModel: AdminAuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        observeState()
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()
            viewModel.signIn(email, pass)
        }

        binding.tvGoToRegister.setOnClickListener {
            val intent = Intent(this, AdminRegisterActivity::class.java)
            startActivity(intent)
        }
    }

    private var isNavigatingToMain = false

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.isVisible = state.isLoading
                    binding.btnLogin.text = if (state.isLoading) "" else "Đăng nhập Quản trị"
                    binding.btnLogin.isEnabled = !state.isLoading

                    val hasError = !state.errorMessage.isNullOrBlank()
                    binding.tvError.isVisible = hasError
                    binding.tvError.text = state.errorMessage ?: ""

                    if (state.isAuthenticated && state.profile != null) {
                        navigateToMain()
                    }
                }
            }
        }
    }

    private fun navigateToMain() {
        if (isNavigatingToMain) return
        isNavigatingToMain = true
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
