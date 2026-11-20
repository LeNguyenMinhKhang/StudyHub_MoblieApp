package com.doan.studyhub.core.data

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

// Singleton khởi tạo SupabaseClient duy nhất cho ứng dụng
object SupabaseClient {
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            requestTimeout = 30.seconds
            install(Auth) {
                // supabase-kt's own callbacks reset the session to Initializing on every background
                // switch (even opening the system file picker). Session fences read that as a sign-out
                // and close group/document screens, so only token refresh is paused below.
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            install(Storage)
            install(Realtime)
        }.also(::pauseTokenRefreshInBackground)
    }

    private fun pauseTokenRefreshInBackground(client: SupabaseClient) {
        val auth = client.auth
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (auth.isAutoRefreshRunning || auth.currentSessionOrNull() == null) return
                scope.launch {
                    // Refreshes first when the token expired while in the background; the session
                    // stays Authenticated meanwhile, so the user identity never flickers to null.
                    try { auth.startAutoRefreshForCurrentSession() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { } // signed out concurrently; nothing to resume
                }
            }

            override fun onStop(owner: LifecycleOwner) = auth.stopAutoRefreshForCurrentSession()
        }
        Handler(Looper.getMainLooper()).post { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
    }
}
