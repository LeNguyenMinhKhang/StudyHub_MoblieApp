package com.doan.studyhub.core.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlin.time.Duration.Companion.seconds

// Singleton khởi tạo SupabaseClient duy nhất cho ứng dụng
object SupabaseClient {
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            requestTimeout = 30.seconds
            install(Auth)
            install(Postgrest)
        }
    }
}
