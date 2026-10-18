package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Subject
import com.doan.studyhub.core.data.model.SubjectFollow
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
class SubjectRemoteDataSource {

    private val postgrest = SupabaseClient.client.postgrest

    // Lấy danh sách môn học đang hoạt động
    suspend fun getActiveSubjects(): List<Subject> {
        return postgrest["subjects"]
            .select {
                filter {
                    eq("is_active", true)
                }
            }
            .decodeList<Subject>()
    }

    // Lấy chi tiết một môn
    suspend fun getSubjectById(subjectId: String): Subject? {
        return postgrest["subjects"]
            .select {
                filter {
                    eq("id", subjectId)
                }
            }
            .decodeSingleOrNull<Subject>()
    }

    // Lấy danh sách ID môn học người dùng đang theo dõi
    suspend fun getFollowedSubjectIds(
        userId: String
    ): List<String> {
        return postgrest["subject_follows"]
            .select {
                filter {
                    eq("user_id", userId)
                }
            }
            .decodeList<SubjectFollow>()
            .map { it.subjectId }
    }

    // Theo dõi một môn học
    suspend fun followSubject(
        userId: String,
        subjectId: String
    ) {
        postgrest["subject_follows"].insert(
            SubjectFollow(
                subjectId = subjectId,
                userId = userId
            )
        )
    }

    // Bỏ theo dõi một môn học
    suspend fun unfollowSubject(
        userId: String,
        subjectId: String
    ) {
        postgrest["subject_follows"].delete {
            filter {
                eq("user_id", userId)
                eq("subject_id", subjectId)
            }
        }
        suspend fun getSubjectById(subjectId: String): Subject? =
            postgrest["subjects"]
                .select {
                    filter {
                        eq("id", subjectId)
                        eq("is_active", true)
                    }
                }
                .decodeList<Subject>()
                .firstOrNull()
    }
}