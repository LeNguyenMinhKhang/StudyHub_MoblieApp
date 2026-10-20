package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Post
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.PostFile
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.model.Subject
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import java.util.UUID

class PostRemoteDataSource {

    private val postgrest = SupabaseClient.client.postgrest
    private val storage = SupabaseClient.client.storage

    private suspend fun ensureProfileExists(userId: String) {
        try {
            val existing = postgrest["profiles"]
                .select {
                    filter { eq("id", userId) }
                }
                .decodeList<Profile>()
                .firstOrNull()

            if (existing == null) {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser != null && currentUser.id == userId) {
                    val metadata = currentUser.userMetadata
                    val username = metadata?.get("username")?.toString()?.trim('"')
                        ?.takeIf { it.isNotBlank() } ?: "user_${userId.take(6)}"
                    val displayName = metadata?.get("display_name")?.toString()?.trim('"')
                        ?.takeIf { it.isNotBlank() } ?: username

                    postgrest["profiles"].insert(
                        Profile(
                            id = userId,
                            username = username,
                            displayName = displayName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    suspend fun getPosts(
        subjectId: String? = null,
        authorId: String? = null,
        type: String? = null
    ): List<PostDetail> {
        val currentUserId = SupabaseClient.client.auth.currentUserOrNull()?.id
        if (!currentUserId.isNullOrBlank()) {
            ensureProfileExists(currentUserId)
        }

        val posts = postgrest["posts"]
            .select {
                filter {
                    if (!subjectId.isNullOrBlank()) eq("subject_id", subjectId.trim())
                    if (!authorId.isNullOrBlank()) eq("author_id", authorId.trim())
                    if (!type.isNullOrBlank() && type != "all") eq("type", type.trim())
                }
                order(column = "created_at", order = Order.DESCENDING)
            }
            .decodeList<Post>()

        if (posts.isEmpty()) return emptyList()

        val authorIds = posts.map { it.authorId.trim() }.filter { it.isNotBlank() }.distinct()
        val subjectIds = posts.mapNotNull { it.subjectId?.trim() }.filter { it.isNotBlank() }.distinct()
        val postIds = posts.mapNotNull { it.id?.trim() }.filter { it.isNotBlank() }

        val authors = if (authorIds.isNotEmpty()) {
            val profileMap = mutableMapOf<String, Profile>()
            try {
                val directProfiles = postgrest["profiles"]
                    .select {
                        filter {
                            isIn("id", authorIds)
                        }
                    }
                    .decodeList<Profile>()
                directProfiles.forEach { profileMap[it.id] = it }
            } catch (e: Exception) {
                // Ignore direct read error
            }

            val missingAuthorIds = authorIds.filter { !profileMap.containsKey(it) }
            if (missingAuthorIds.isNotEmpty()) {
                val profileRemote = ProfileRemoteDataSource()
                missingAuthorIds.forEach { id ->
                    try {
                        val pub = profileRemote.getPublicById(id)
                        if (pub != null) {
                            profileMap[id] = Profile(
                                id = pub.id,
                                username = pub.username,
                                displayName = pub.displayName,
                                avatarUrl = pub.avatarUrl,
                                bio = pub.bio,
                                createdAt = pub.createdAt
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
            profileMap
        } else emptyMap()

        val subjects = if (subjectIds.isNotEmpty()) {
            try {
                postgrest["subjects"]
                    .select {
                        filter {
                            isIn("id", subjectIds)
                        }
                    }
                    .decodeList<Subject>()
                    .associateBy { it.id }
            } catch (e: Exception) {
                try {
                    postgrest["subjects"]
                        .select()
                        .decodeList<Subject>()
                        .associateBy { it.id }
                } catch (e2: Exception) {
                    emptyMap()
                }
            }
        } else emptyMap()

        val files = if (postIds.isNotEmpty()) {
            try {
                postgrest["post_files"]
                    .select {
                        filter {
                            isIn("post_id", postIds)
                        }
                    }
                    .decodeList<PostFile>()
                    .groupBy { it.postId }
            } catch (e: Exception) {
                emptyMap()
            }
        } else emptyMap()

        return posts.map { post ->
            PostDetail(
                post = post,
                author = authors[post.authorId],
                subject = subjects[post.subjectId],
                files = files[post.id].orEmpty()
            )
        }
    }

    suspend fun getPostDetail(postId: String): PostDetail? {
        val post = postgrest["posts"]
            .select {
                filter {
                    eq("id", postId)
                }
            }
            .decodeList<Post>()
            .firstOrNull() ?: return null

        val author = try {
            postgrest["profiles"]
                .select {
                    filter {
                        eq("id", post.authorId)
                    }
                }
                .decodeList<Profile>()
                .firstOrNull()
        } catch (e: Exception) {
            null
        } ?: try {
            ProfileRemoteDataSource().getPublicById(post.authorId)?.let { pub ->
                Profile(
                    id = pub.id,
                    username = pub.username,
                    displayName = pub.displayName,
                    avatarUrl = pub.avatarUrl,
                    bio = pub.bio,
                    createdAt = pub.createdAt
                )
            }
        } catch (_: Exception) {
            null
        }

        val subject = if (!post.subjectId.isNullOrBlank()) {
            try {
                postgrest["subjects"]
                    .select {
                        filter {
                            eq("id", post.subjectId)
                        }
                    }
                    .decodeList<Subject>()
                    .firstOrNull()
            } catch (e: Exception) {
                null
            }
        } else null

        val files = try {
            postgrest["post_files"]
                .select {
                    filter {
                        eq("post_id", postId)
                    }
                }
                .decodeList<PostFile>()
        } catch (e: Exception) {
            emptyList()
        }

        return PostDetail(
            post = post,
            author = author,
            subject = subject,
            files = files
        )
    }

    suspend fun createPost(request: com.doan.studyhub.core.data.model.CreatePostRequest): Post {
        ensureProfileExists(request.authorId)
        return postgrest["posts"]
            .insert(request) {
                select()
            }
            .decodeList<Post>()
            .first()
    }

    suspend fun uploadPostFile(
        postId: String,
        fileName: String,
        fileBytes: ByteArray,
        fileType: String?
    ): PostFile {
        val cleanFileName = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val storagePath = "posts/$postId/${UUID.randomUUID()}_$cleanFileName"

        var publicUrl: String? = null
        val bucketsToTry = listOf("documents", "posts")
        var lastException: Exception? = null

        for (bucketName in bucketsToTry) {
            try {
                val bucket = storage.from(bucketName)
                bucket.upload(path = storagePath, data = fileBytes) {
                    upsert = true
                }
                publicUrl = bucket.publicUrl(path = storagePath)
                break
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (publicUrl == null) {
            throw lastException ?: RuntimeException("Upload failed")
        }

        val postFileRequest = com.doan.studyhub.core.data.model.CreatePostFileRequest(
            postId = postId,
            fileUrl = publicUrl,
            fileName = fileName,
            fileType = fileType ?: "application/octet-stream",
            fileSize = fileBytes.size.toLong()
        )

        return postgrest["post_files"]
            .insert(postFileRequest) {
                select()
            }
            .decodeList<PostFile>()
            .first()
    }

    suspend fun updatePost(
        postId: String,
        title: String,
        content: String?,
        type: String?,
        subjectId: String?
    ) {
        postgrest["posts"]
            .update({
                set("title", title)
                set("content", content)
                set("type", type)
                set("subject_id", subjectId)
            }) {
                filter {
                    eq("id", postId)
                }
            }
    }

    suspend fun deletePost(postId: String) {
        try {
            postgrest["post_files"].delete {
                filter {
                    eq("post_id", postId)
                }
            }
        } catch (_: Exception) {}

        postgrest["posts"]
            .delete {
                filter {
                    eq("id", postId)
                }
            }
    }

    suspend fun deletePostFile(fileId: String) {
        postgrest["post_files"]
            .delete {
                filter {
                    eq("id", fileId)
                }
            }
    }
}
