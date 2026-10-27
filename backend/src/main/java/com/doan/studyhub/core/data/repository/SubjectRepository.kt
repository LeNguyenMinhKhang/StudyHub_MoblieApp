
package com.doan.studyhub.core.data.repository

import com.doan.studyhub.core.data.model.Subject
import com.doan.studyhub.core.data.remote.SubjectRemoteDataSource

class SubjectRepository(
    private val remoteDataSource: SubjectRemoteDataSource =
        SubjectRemoteDataSource()
) {
    suspend fun getActiveSubjects(): List<Subject> {
        return remoteDataSource.getActiveSubjects()
    }

    suspend fun getSubjectById(subjectId: String): Subject? {
        return remoteDataSource.getSubjectById(subjectId)
    }

    suspend fun getFollowedSubjectIds(userId: String): List<String> {
        return remoteDataSource.getFollowedSubjectIds(userId)
    }

    suspend fun followSubject(userId: String, subjectId: String) {
        remoteDataSource.followSubject(userId, subjectId)
    }

    suspend fun unfollowSubject(userId: String, subjectId: String) {
        remoteDataSource.unfollowSubject(userId, subjectId)
    }
}
