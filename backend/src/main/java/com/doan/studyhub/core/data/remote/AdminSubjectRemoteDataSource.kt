
package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Subject
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AdminSubjectRemoteDataSource {

    private val postgrest = SupabaseClient.client.postgrest

    // Lấy toàn bộ môn học (bao gồm active và inactive)
    suspend fun getAllSubjects(): List<Subject> {
        return postgrest["subjects"]
            .select()
            .decodeList<Subject>()
    }

    // Thêm môn học
    suspend fun createSubject(
        name: String,
        description: String?
    ) {
        postgrest["subjects"].insert(
            buildJsonObject {
                put("name", name)
                put("description", description)
                put("is_active", true)
            }
        )
    }

    // Cập nhật tên và mô tả môn học
    suspend fun updateSubject(
        subjectId: String,
        name: String,
        description: String?
    ) {
        postgrest["subjects"].update(
            {
                set("name", name)
                set("description", description)
            }
        ) {
            filter {
                eq("id", subjectId)
            }
        }
    }

    // Bật / tắt trạng thái môn học
    suspend fun updateSubjectStatus(
        subjectId: String,
        isActive: Boolean
    ) {
        postgrest["subjects"].update(
            {
                set("is_active", isActive)
            }
        ) {
            filter {
                eq("id", subjectId)
            }
        }
    }
}
