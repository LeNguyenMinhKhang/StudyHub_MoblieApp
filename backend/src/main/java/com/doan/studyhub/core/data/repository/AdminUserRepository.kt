package com.doan.studyhub.core.data.repository

import com.doan.studyhub.core.data.model.AdminUser
import com.doan.studyhub.core.data.remote.AdminUserRemoteDataSource

class AdminUserRepository(
    private val remoteDataSource: AdminUserRemoteDataSource =
        AdminUserRemoteDataSource()
) {

    suspend fun listUsers(
        search: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<AdminUser> {
        return remoteDataSource.listUsers(
            search = search,
            limit = limit,
            offset = offset
        )
    }
}