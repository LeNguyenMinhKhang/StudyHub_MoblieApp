
package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.Comment
import com.doan.studyhub.core.data.model.CreateLikeRequest
import com.doan.studyhub.core.data.model.Hashtag
import com.doan.studyhub.core.data.model.HashtagUtils
import com.doan.studyhub.core.data.model.Like
import com.doan.studyhub.core.data.model.Post
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.PostFile
import com.doan.studyhub.core.data.model.PostHashtag
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.model.SavedPost
import com.doan.studyhub.core.data.model.Subject
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
                        ?.takeIf { it.isNotBlank() }
                        ?: "user_${userId.take(6)}"

                    val displayName = metadata?.get("display_name")?.toString()?.trim('"')
                        ?.takeIf { it.isNotBlank() }
                        ?: username

                    postgrest["profiles"].insert(
                        Profile(
                            id = userId,
                            username = username,
                            displayName = displayName
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Bỏ qua nếu không thể tạo hoặc kiểm tra profile.
        }
    }

    // Lấy danh sách bài viết
    suspend fun getPosts(
        subjectId: String? = null,
        authorId: String? = null,
        type: String? = null,
        limit: Long? = null,
        offset: Long? = null
    ): List<PostDetail> {

        val currentUserId = SupabaseClient.client.auth.currentUserOrNull()?.id

        if (!currentUserId.isNullOrBlank()) {
            ensureProfileExists(currentUserId)
        }

        val rawPosts = postgrest["posts"]
            .select {
                filter {
                    if (!subjectId.isNullOrBlank()) eq("subject_id", subjectId.trim())
                    if (!authorId.isNullOrBlank()) eq("author_id", authorId.trim())

                    if (!type.isNullOrBlank() && type != "all") {
                        ilike("type", "${type.trim()}%")
                    }
                }

                order(column = "created_at", order = Order.DESCENDING)

                if (limit != null && offset != null) {
                    range(from = offset, to = offset + limit - 1)
                } else if (limit != null) {
                    limit(limit)
                }
            }
            .decodeList<Post>()

        val friendIds = try {
            if (!currentUserId.isNullOrBlank()) {
                FriendshipRemoteDataSource()
                    .list(
                        com.doan.studyhub.core.data.model.FriendshipListKind.FRIENDS,
                        null,
                        100
                    )
                    .items
                    .map { it.peer.id }
                    .toSet()
            } else {
                emptySet()
            }
        } catch (_: Exception) {
            emptySet()
        }

        val posts = rawPosts.filter { post ->
            val (category, visibility) =
                com.doan.studyhub.core.data.model.PostTypeUtils.parse(post.type)

            val matchesCategory =
                type.isNullOrBlank() ||
                        type == "all" ||
                        category == type.trim().lowercase()

            val matchesVisibility = when (visibility) {
                com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PRIVATE ->
                    post.authorId == currentUserId

                com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_FRIENDS ->
                    post.authorId == currentUserId || friendIds.contains(post.authorId)

                else -> true
            }

            matchesCategory && matchesVisibility
        }

        return enrichPosts(posts)
    }

    // Ghép tác giả, môn học, file, lượt thích và số bình luận cho danh sách bài viết,
    // giữ nguyên thứ tự đầu vào. Dùng chung cho bảng tin và kết quả tìm kiếm.
    suspend fun enrichPosts(posts: List<Post>): List<PostDetail> {
        if (posts.isEmpty()) return emptyList()

        val currentUserId = SupabaseClient.client.auth.currentUserOrNull()?.id

        val authorIds = posts.map { it.authorId.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val subjectIds = posts.mapNotNull { it.subjectId?.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val postIds = posts.mapNotNull { it.id?.trim() }
            .filter { it.isNotBlank() }

        // Lấy thông tin Like
        val likeSummary = getLikeSummary(postIds, currentUserId)

        // Lấy số lượng bình luận của từng bài viết
        val commentCounts = getCommentCounts(postIds)

        // Lấy thông tin tác giả
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

                directProfiles.forEach {
                    profileMap[it.id] = it
                }
            } catch (_: Exception) {
                // Bỏ qua lỗi đọc profile trực tiếp.
            }

            val missingAuthorIds = authorIds.filter {
                !profileMap.containsKey(it)
            }

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
                    } catch (_: Exception) {
                    }
                }
            }

            profileMap
        } else {
            emptyMap()
        }

        // Lấy thông tin môn học
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
            } catch (_: Exception) {
                try {
                    postgrest["subjects"]
                        .select()
                        .decodeList<Subject>()
                        .associateBy { it.id }
                } catch (_: Exception) {
                    emptyMap()
                }
            }
        } else {
            emptyMap()
        }

        // Lấy danh sách file đính kèm
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
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        // Lấy trạng thái lưu bài viết của người dùng hiện tại
        val savedPostIds = if (!currentUserId.isNullOrBlank() && postIds.isNotEmpty()) {
            try {
                postgrest["saved_posts"]
                    .select {
                        filter {
                            eq("user_id", currentUserId)
                            isIn("post_id", postIds)
                        }
                    }
                    .decodeList<SavedPost>()
                    .map { it.postId }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }
        } else {
            emptySet()
        }

        // Lấy danh sách hashtag liên kết với các bài viết
        val hashtagsByPostId = if (postIds.isNotEmpty()) {
            try {
                val postHashtags = postgrest["post_hashtags"]
                    .select {
                        filter {
                            isIn("post_id", postIds)
                        }
                    }
                    .decodeList<PostHashtag>()

                val hashtagIds = postHashtags.map { it.hashtagId }.distinct()
                val hashtagMap = if (hashtagIds.isNotEmpty()) {
                    postgrest["hashtags"]
                        .select {
                            filter {
                                isIn("id", hashtagIds)
                            }
                        }
                        .decodeList<Hashtag>()
                        .associate { (it.id ?: "") to it.name }
                } else {
                    emptyMap()
                }

                postHashtags.groupBy({ it.postId }, { hashtagMap[it.hashtagId] ?: "" })
                    .mapValues { entry -> entry.value.filter { it.isNotBlank() } }
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        // Kết hợp dữ liệu bài viết
        return posts.map { post ->
            val (likeCount, isLiked) = post.id
                ?.let { likeSummary[it] }
                ?: (0 to false)

            PostDetail(
                post = post,
                author = authors[post.authorId],
                subject = subjects[post.subjectId],
                files = files[post.id].orEmpty(),
                likeCount = likeCount,
                isLikedByCurrentUser = isLiked,
                commentCount = commentCounts[post.id] ?: 0,
                isSavedByCurrentUser = post.id?.let { savedPostIds.contains(it) } ?: false,
                hashtags = post.id?.let { hashtagsByPostId[it] }.orEmpty()
            )
        }
    }

    // Lấy chi tiết bài viết
    suspend fun getPostDetail(postId: String): PostDetail? {

        val post = postgrest["posts"]
            .select {
                filter {
                    eq("id", postId)
                }
            }
            .decodeList<Post>()
            .firstOrNull()
            ?: return null

        val currentUserId = SupabaseClient.client.auth.currentUserOrNull()?.id

        val (_, visibility) =
            com.doan.studyhub.core.data.model.PostTypeUtils.parse(post.type)

        if (
            visibility == com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PRIVATE &&
            post.authorId != currentUserId
        ) {
            return null
        }

        if (
            visibility == com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_FRIENDS &&
            post.authorId != currentUserId
        ) {
            val friendIds = try {
                if (!currentUserId.isNullOrBlank()) {
                    FriendshipRemoteDataSource()
                        .list(
                            com.doan.studyhub.core.data.model.FriendshipListKind.FRIENDS,
                            null,
                            100
                        )
                        .items
                        .map { it.peer.id }
                        .toSet()
                } else {
                    emptySet()
                }
            } catch (_: Exception) {
                emptySet()
            }

            if (!friendIds.contains(post.authorId)) {
                return null
            }
        }

        // Lấy tác giả
        val author = try {
            postgrest["profiles"]
                .select {
                    filter {
                        eq("id", post.authorId)
                    }
                }
                .decodeList<Profile>()
                .firstOrNull()
        } catch (_: Exception) {
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

        // Lấy môn học
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
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        // Lấy file đính kèm
        val files = try {
            postgrest["post_files"]
                .select {
                    filter {
                        eq("post_id", postId)
                    }
                }
                .decodeList<PostFile>()
        } catch (_: Exception) {
            emptyList()
        }

        // Lấy thông tin Like
        val likeSummary = getLikeSummary(
            postIds = listOf(postId),
            currentUserId = currentUserId
        )

        val (likeCount, isLiked) = likeSummary[postId] ?: (0 to false)

        // Lấy số lượng bình luận
        val commentCount = getCommentCounts(listOf(postId))[postId] ?: 0

        return PostDetail(
            post = post,
            author = author,
            subject = subject,
            files = files,
            likeCount = likeCount,
            isLikedByCurrentUser = isLiked,
            commentCount = commentCount
        )
    }

    // Đếm số bình luận của từng bài viết
    // Bao gồm bình luận gốc và các câu trả lời.
    private suspend fun getCommentCounts(
        postIds: List<String>
    ): Map<String, Int> {
        if (postIds.isEmpty()) return emptyMap()

        return try {
            postgrest["comments"]
                .select {
                    filter {
                        isIn("post_id", postIds)
                    }
                }
                .decodeList<Comment>()
                .groupingBy { it.postId }
                .eachCount()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // Tạo bài viết
    suspend fun createPost(
        request: com.doan.studyhub.core.data.model.CreatePostRequest,
        hashtags: List<String> = emptyList()
    ): Post {
        ensureProfileExists(request.authorId)

        val createdPost = postgrest["posts"]
            .insert(request) {
                select()
            }
            .decodeList<Post>()
            .first()

        val postId = createdPost.id
        if (!postId.isNullOrBlank() && hashtags.isNotEmpty()) {
            attachHashtags(postId, hashtags)
        }

        return createdPost
    }

    suspend fun attachHashtags(postId: String, rawTags: List<String>) {
        val normalized = HashtagUtils.normalizeList(rawTags)
        if (normalized.isEmpty()) return

        for (tag in normalized) {
            try {
                val existing = postgrest["hashtags"]
                    .select {
                        filter {
                            eq("name", tag)
                        }
                    }
                    .decodeList<Hashtag>()
                    .firstOrNull()

                val hashtagId = existing?.id ?: run {
                    postgrest["hashtags"]
                        .insert(Hashtag(name = tag)) {
                            select()
                        }
                        .decodeList<Hashtag>()
                        .firstOrNull()?.id
                }

                if (!hashtagId.isNullOrBlank()) {
                    postgrest["post_hashtags"].insert(
                        PostHashtag(
                            postId = postId,
                            hashtagId = hashtagId
                        )
                    )
                }
            } catch (_: Exception) {
                // Bỏ qua lỗi conflict tag
            }
        }
    }

    // Upload file bài viết
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

        val postFileRequest =
            com.doan.studyhub.core.data.model.CreatePostFileRequest(
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

    // Cập nhật bài viết
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

    // Xóa bài viết
    suspend fun deletePost(postId: String) {

        try {
            postgrest["post_files"].delete {
                filter {
                    eq("post_id", postId)
                }
            }
        } catch (_: Exception) {
        }

        postgrest["posts"]
            .delete {
                filter {
                    eq("id", postId)
                }
            }
    }

    // Xóa file đính kèm
    suspend fun deletePostFile(fileId: String) {
        postgrest["post_files"]
            .delete {
                filter {
                    eq("id", fileId)
                }
            }
    }

    // Lấy số lượt Like và trạng thái Like
    suspend fun getLikeSummary(
        postIds: List<String>,
        currentUserId: String?
    ): Map<String, Pair<Int, Boolean>> {

        if (postIds.isEmpty()) return emptyMap()

        val likes = postgrest["likes"]
            .select {
                filter {
                    isIn("post_id", postIds)
                }
            }
            .decodeList<Like>()

        return likes
            .groupBy { it.postId }
            .mapValues { (_, postLikes) ->

                val likeCount = postLikes.size

                val isLiked = !currentUserId.isNullOrBlank() &&
                        postLikes.any { it.userId == currentUserId }

                Pair(likeCount, isLiked)
            }
    }

    // Like / Unlike bài viết
    suspend fun toggleLike(postId: String): Boolean {

        val currentUserId = SupabaseClient.client.auth
            .currentUserOrNull()?.id
            ?: throw IllegalStateException(
                "Bạn cần đăng nhập để Like bài viết."
            )

        val existingLike = postgrest["likes"]
            .select {
                filter {
                    eq("post_id", postId)
                    eq("user_id", currentUserId)
                }
            }
            .decodeList<Like>()
            .firstOrNull()

        return if (existingLike != null) {

            postgrest["likes"].delete {
                filter {
                    eq("post_id", postId)
                    eq("user_id", currentUserId)
                }
            }

            false

        } else {

            postgrest["likes"].insert(
                CreateLikeRequest(
                    postId = postId,
                    userId = currentUserId
                )
            )

            true
        }
    }

    // Toggle lưu / bỏ lưu bài viết
    suspend fun toggleSavePost(postId: String): Boolean {
        val currentUserId = SupabaseClient.client.auth
            .currentUserOrNull()?.id
            ?: throw IllegalStateException("Bạn cần đăng nhập để lưu bài viết.")

        return try {
            val response = postgrest.rpc(
                function = "toggle_saved_post",
                parameters = buildJsonObject {
                    put("p_post_id", postId)
                }
            )
            response.data.trim().toBoolean()
        } catch (e: Exception) {
            // Fallback sang truy vấn bảng nếu RPC chưa sẵn sàng
            val existing = postgrest["saved_posts"]
                .select {
                    filter {
                        eq("user_id", currentUserId)
                        eq("post_id", postId)
                    }
                }
                .decodeList<SavedPost>()
                .firstOrNull()

            if (existing != null) {
                postgrest["saved_posts"].delete {
                    filter {
                        eq("user_id", currentUserId)
                        eq("post_id", postId)
                    }
                }
                false
            } else {
                postgrest["saved_posts"].insert(
                    SavedPost(userId = currentUserId, postId = postId)
                )
                true
            }
        }
    }

    // Lấy danh sách bài viết đã lưu của người dùng hiện tại
    suspend fun getSavedPosts(
        limit: Long? = null,
        offset: Long? = null
    ): List<PostDetail> {
        val currentUserId = SupabaseClient.client.auth.currentUserOrNull()?.id
            ?: return emptyList()

        val savedItems = postgrest["saved_posts"]
            .select {
                filter {
                    eq("user_id", currentUserId)
                }
                order(column = "created_at", order = Order.DESCENDING)
                if (limit != null && offset != null) {
                    range(from = offset, to = offset + limit - 1)
                } else if (limit != null) {
                    limit(limit)
                }
            }
            .decodeList<SavedPost>()

        val postIds = savedItems.map { it.postId }.distinct()
        if (postIds.isEmpty()) return emptyList()

        val rawPosts = postgrest["posts"]
            .select {
                filter {
                    isIn("id", postIds)
                }
            }
            .decodeList<Post>()

        val postMap = rawPosts.associateBy { it.id }
        val orderedPosts = postIds.mapNotNull { postMap[it] }

        val friendIds = try {
            FriendshipRemoteDataSource()
                .list(
                    com.doan.studyhub.core.data.model.FriendshipListKind.FRIENDS,
                    null,
                    100
                )
                .items
                .map { it.peer.id }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val visiblePosts = orderedPosts.filter { post ->
            val (_, visibility) = com.doan.studyhub.core.data.model.PostTypeUtils.parse(post.type)
            when (visibility) {
                com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PUBLIC -> true
                com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_FRIENDS ->
                    post.authorId == currentUserId || friendIds.contains(post.authorId)
                com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PRIVATE ->
                    post.authorId == currentUserId
                else -> true
            }
        }

        return enrichPosts(visiblePosts)
    }
}
