package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.DocumentSearchItem
import com.doan.studyhub.core.data.model.Post
import com.doan.studyhub.core.data.model.ProfileSearchItem
import com.doan.studyhub.core.data.model.SearchPage
import com.doan.studyhub.core.data.model.SearchTab
import com.doan.studyhub.core.data.model.Subject
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Calls the authenticated-only search RPCs; ranking and authorization happen on the server. */
open class SearchRemoteDataSource {
    private val postgrest get() = SupabaseClient.client.postgrest

    open suspend fun profiles(query: String, limit: Int, offset: Int): SearchPage<ProfileSearchItem> =
        call(SearchTab.USERS, query, limit, offset, ProfileSearchItem.serializer())

    open suspend fun posts(query: String, limit: Int, offset: Int): SearchPage<Post> =
        call(SearchTab.POSTS, query, limit, offset, Post.serializer())

    open suspend fun subjects(query: String, limit: Int, offset: Int): SearchPage<Subject> =
        call(SearchTab.SUBJECTS, query, limit, offset, Subject.serializer())

    open suspend fun documents(query: String, limit: Int, offset: Int): SearchPage<DocumentSearchItem> =
        call(SearchTab.DOCUMENTS, query, limit, offset, DocumentSearchItem.serializer())

    private suspend fun <T> call(tab: SearchTab, query: String, limit: Int, offset: Int, item: KSerializer<T>): SearchPage<T> {
        val result = postgrest.rpc(tab.rpc, buildJsonObject {
            put("p_query", query)
            put("p_limit", limit)
            put("p_offset", offset)
        })
        return decodeSearchPage(result.data, item)
    }
}

// Post rows come from to_jsonb(posts), so tolerate columns the client model does not declare.
private val searchJson = Json { ignoreUnknownKeys = true }

internal fun <T> decodeSearchPage(raw: String, item: KSerializer<T>): SearchPage<T> =
    searchJson.decodeFromString(SearchPage.serializer(item), raw)
