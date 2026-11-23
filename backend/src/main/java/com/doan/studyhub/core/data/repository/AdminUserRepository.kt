
package com.doan.studyhub.core.data.repository

import com.doan.studyhub.core.data.model.AdminUser
import com.doan.studyhub.core.data.remote.AdminUserRemoteDataSource

class AdminUserRepository(
    private val remoteDataSource: AdminUserRemoteDataSource =
        AdminUserRemoteDataSource()
) {

    suspend fun listUsers(
        search: String? = null,
        role: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<AdminUser> {
        return remoteDataSource.listUsers(
            search = search,
            role = role,
            limit = limit,
            offset = offset
        )
    }

    // Ban người dùng
    suspend fun banUser(userId: String) {
        remoteDataSource.banUser(userId)
    }

    // Gỡ ban người dùng
    suspend fun unbanUser(userId: String) {
        remoteDataSource.unbanUser(userId)
    }
}
