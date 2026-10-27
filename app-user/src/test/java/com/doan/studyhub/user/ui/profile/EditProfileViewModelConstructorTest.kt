package com.doan.studyhub.user.ui.profile

import android.app.Application
import org.junit.Test

class EditProfileViewModelConstructorTest {
    @Test
    fun `default Android factory can find the Application constructor`() {
        // AndroidViewModelFactory uses this exact public JVM signature via reflection.
        EditProfileViewModel::class.java.getConstructor(Application::class.java)
    }
}
