package com.doan.studyhub.user.ui.post

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.PostTypeUtils
import com.doan.studyhub.user.R

/** Builds the plain text handed to Android's share sheet. Pure so it can be unit tested. */
object PostShareText {
    const val MAX_CONTENT_CHARS = 1500

    /** Only public posts, or the viewer's own posts, may leave the app. */
    fun canShare(item: PostDetail, currentUserId: String?): Boolean {
        val (_, visibility) = PostTypeUtils.parse(item.post.type)
        return visibility == PostTypeUtils.VISIBILITY_PUBLIC ||
            (!currentUserId.isNullOrBlank() && item.post.authorId == currentUserId)
    }

    fun build(item: PostDetail, authorName: String?, footer: String): String = buildString {
        val post = item.post
        appendLine(post.title.trim())
        val meta = listOfNotNull(
            authorName?.trim()?.takeIf { it.isNotEmpty() },
            item.subject?.name?.trim()?.takeIf { it.isNotEmpty() }
        )
        if (meta.isNotEmpty()) appendLine(meta.joinToString(" · "))
        post.content?.trim()?.takeIf { it.isNotEmpty() }?.let { content ->
            appendLine()
            appendLine(if (content.length > MAX_CONTENT_CHARS) content.take(MAX_CONTENT_CHARS).trimEnd() + "…" else content)
        }
        val files = item.files.filter { it.fileUrl.isNotBlank() }
        if (files.isNotEmpty()) {
            appendLine()
            files.forEach { appendLine("📎 ${it.fileName}: ${it.fileUrl}") }
        }
        appendLine()
        append(footer)
    }
}

/** Opens the system share sheet for [item]; non-public posts of other users are refused. */
fun Fragment.sharePost(item: PostDetail, authorName: String?, currentUserId: String?) {
    val context = context ?: return
    if (!PostShareText.canShare(item, currentUserId)) {
        Toast.makeText(context, R.string.post_share_not_public, Toast.LENGTH_SHORT).show()
        return
    }
    val text = PostShareText.build(item, authorName, getString(R.string.post_share_footer))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, item.post.title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        startActivity(Intent.createChooser(send, getString(R.string.post_share_title)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.post_share_unavailable, Toast.LENGTH_SHORT).show()
    }
}
