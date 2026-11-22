
package com.doan.studyhub.core.data.repository

import com.doan.studyhub.core.data.model.Subject
import com.doan.studyhub.core.data.remote.AdminSubjectRemoteDataSource

class AdminSubjectRepository(
    private val remoteDataSource: AdminSubjectRemoteDataSource =
        AdminSubjectRemoteDataSource()
) {

    // Lấy toàn bộ môn học
    suspend fun getAllSubjects(): List<Subject> {
        return remoteDataSource.getAllSubjects()
    }

    // Thêm môn học
    suspend fun createSubject(
        name: String,
        description: String?
    ) {
        remoteDataSource.createSubject(name, description)
    }

    // Cập nhật môn học
    suspend fun updateSubject(
        subjectId: String,
        name: String,
        description: String?
    ) {
        remoteDataSource.updateSubject(
            subjectId = subjectId,
            name = name,
            description = description
        )
    }

    // Bật / tắt môn học
    suspend fun updateSubjectStatus(
        subjectId: String,
        isActive: Boolean
    ) {
        remoteDataSource.updateSubjectStatus(
            subjectId = subjectId,
            isActive = isActive
        )
    }
}
