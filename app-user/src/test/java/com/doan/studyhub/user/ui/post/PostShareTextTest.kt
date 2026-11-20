package com.doan.studyhub.user.ui.post

import com.doan.studyhub.core.data.model.Post
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.PostFile
import com.doan.studyhub.core.data.model.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostShareTextTest {
    private val author = "author-1"
    private fun detail(type: String? = "knowledge:public", content: String? = "Nội dung", files: List<PostFile> = emptyList()) =
        PostDetail(
            post = Post(id = "p1", authorId = author, type = type, title = "  Tiêu đề  ", content = content),
            subject = Subject(id = "s1", name = "Toán"),
            files = files
        )

    @Test fun publicPostsAreShareableByAnyone() {
        assertTrue(PostShareText.canShare(detail(), "someone-else"))
        assertTrue(PostShareText.canShare(detail(type = null), null))
    }

    @Test fun nonPublicPostsAreShareableOnlyByAuthor() {
        assertFalse(PostShareText.canShare(detail(type = "none:friends"), "someone-else"))
        assertFalse(PostShareText.canShare(detail(type = "none:private"), null))
        assertTrue(PostShareText.canShare(detail(type = "none:private"), author))
    }

    @Test fun buildsTitleMetaContentFilesAndFooter() {
        val file = PostFile(postId = "p1", fileUrl = "https://x/a.pdf", fileName = "a.pdf")
        val blank = PostFile(postId = "p1", fileUrl = " ", fileName = "b.pdf")
        val text = PostShareText.build(detail(files = listOf(file, blank)), "An", "— footer")
        assertEquals("Tiêu đề\nAn · Toán\n\nNội dung\n\n📎 a.pdf: https://x/a.pdf\n\n— footer", text)
    }

    @Test fun omitsEmptyPartsAndTruncatesLongContent() {
        assertEquals("Tiêu đề\nToán\n\n— f", PostShareText.build(detail(content = "  "), "  ", "— f"))
        val long = "a".repeat(PostShareText.MAX_CONTENT_CHARS + 10)
        val text = PostShareText.build(detail(content = long), null, "f")
        assertTrue(text.contains("a".repeat(PostShareText.MAX_CONTENT_CHARS) + "…\n"))
        assertFalse(text.contains("a".repeat(PostShareText.MAX_CONTENT_CHARS + 1)))
    }
}
