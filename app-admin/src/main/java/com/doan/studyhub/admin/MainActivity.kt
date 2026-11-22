
package com.doan.studyhub.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.doan.studyhub.admin.ui.subjects.AdminSubjectScreen
import com.doan.studyhub.admin.ui.subjects.AdminSubjectViewModel

class MainActivity : ComponentActivity() {

    private val adminSubjectViewModel: AdminSubjectViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(AdminSubjectViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return AdminSubjectViewModel() as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AdminSubjectScreen(
                        viewModel = adminSubjectViewModel
                    )
                }
            }
        }
    }
}
