package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.DomainException
import com.doan.studyhub.common.ErrorLogger
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.local.AvatarPendingStore
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.remote.AvatarRemoteDataSource
import com.doan.studyhub.core.data.remote.ProfileRemoteDataSource
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AvatarRepositoryTest {

    private val testUserId = "11111111-1111-1111-1111-111111111111"
    private val otherUserId = "22222222-2222-2222-2222-222222222222"
    private val oldAvatarUrl = "https://example.supabase.co/storage/v1/object/public/avatars/11111111-1111-1111-1111-111111111111/old-photo.jpg"

    private lateinit var fakeRemote: FakeAvatarRemoteDataSource
    private lateinit var fakeProfileRemote: FakeProfileRemoteDataSource
    private lateinit var fakePendingStore: FakeAvatarPendingStore
    private lateinit var repository: AvatarRepository
    private lateinit var dummyDraft: File

    class FakeAvatarRemoteDataSource : AvatarRemoteDataSource() {
        var uploadShouldFail: Exception? = null
        val uploadedFiles = mutableMapOf<String, File>()
        val deletedPaths = mutableListOf<String>()
        var deleteShouldThrow: Exception? = null

        override suspend fun upload(objectPath: String, file: File) {
            uploadShouldFail?.let { throw it }
            uploadedFiles[objectPath] = file
        }

        override suspend fun deleteIfUnreferenced(objectPath: String) {
            deleteShouldThrow?.let { throw it }
            deletedPaths += objectPath
        }

        override fun publicUrl(objectPath: String): String =
            "https://example.supabase.co/storage/v1/object/public/avatars/$objectPath"
    }

    class FakeProfileRemoteDataSource : ProfileRemoteDataSource() {
        var myProfile: Profile? = null
        var updateAvatarShouldFail: Exception? = null
        var lastObjectPath: String? = null
        var lastExpectedUrl: String? = null

        override suspend fun getMine(): Profile? = myProfile

        override suspend fun updateMyAvatar(objectPath: String?, expectedAvatarUrl: String?): Profile? {
            updateAvatarShouldFail?.let { throw it }
            lastObjectPath = objectPath
            lastExpectedUrl = expectedAvatarUrl
            val newUrl = objectPath?.let { "https://example.supabase.co/storage/v1/object/public/avatars/$it" }
            return Profile(
                id = "11111111-1111-1111-1111-111111111111",
                username = "testuser",
                displayName = "Test User",
                bio = null,
                avatarUrl = newUrl,
                createdAt = "2026-01-01"
            ).also { myProfile = it }
        }
    }

    class FakeAvatarPendingStore : AvatarPendingStore() {
        val records = mutableMapOf<String, Record>()

        override suspend fun save(userId: String, record: Record) {
            records[userId] = record
        }

        override suspend fun load(userId: String): Record? = records[userId]

        override suspend fun clear(userId: String) {
            records.remove(userId)
        }
    }

    @Before
    fun setup() {
        dummyDraft = File.createTempFile("avatar_draft", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            deleteOnExit()
        }
        fakeRemote = FakeAvatarRemoteDataSource()
        fakeProfileRemote = FakeProfileRemoteDataSource()
        fakePendingStore = FakeAvatarPendingStore()
        repository = AvatarRepository(
            context = null,
            local = null,
            remote = fakeRemote,
            profileRemote = fakeProfileRemote,
            pendingStore = fakePendingStore,
            api = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> }),
            ownerCheck = { it == testUserId }
        )
    }

    @Test
    fun `commitRemote success orchestrates upload, publish, cleanup and returns new profile`() = runBlocking {
        val result = repository.commitRemote(testUserId, dummyDraft, oldAvatarUrl)

        assertTrue(result is ApiResponse.Success)
        val profile = (result as ApiResponse.Success).data
        assertNotNull(profile.avatarUrl)
        assertTrue(profile.avatarUrl!!.contains("$testUserId/"))

        // Check upload
        assertEquals(1, fakeRemote.uploadedFiles.size)
        val uploadedKey = fakeRemote.uploadedFiles.keys.first()
        assertTrue(uploadedKey.startsWith("$testUserId/"))

        // Check old object was deleted
        assertTrue(fakeRemote.deletedPaths.contains("$testUserId/old-photo.jpg"))

        // Check pending store is cleared after full success
        assertNull(fakePendingStore.records[testUserId])
    }

    @Test
    fun `commitRemote upload failure returns failure and does not call publish`() = runBlocking {
        fakeRemote.uploadShouldFail = IOException("Simulated network upload error")

        val result = repository.commitRemote(testUserId, dummyDraft, oldAvatarUrl)

        assertTrue(result is ApiResponse.Failure)
        assertEquals(ErrorType.AVATAR_UPLOAD_FAILED, (result as ApiResponse.Failure).error.type)
        assertNull(fakeProfileRemote.lastObjectPath)
        assertNull(fakePendingStore.records[testUserId])
    }

    @Test
    fun `commitRemote publication rejection cleans up new object and returns failure`() = runBlocking {
        fakeProfileRemote.updateAvatarShouldFail = DomainException(ErrorType.PROFILE_NOT_FOUND)

        val result = repository.commitRemote(testUserId, dummyDraft, oldAvatarUrl)

        assertTrue(result is ApiResponse.Failure)
        assertEquals(ErrorType.PROFILE_NOT_FOUND, (result as ApiResponse.Failure).error.type)

        // Verifies cleanup of newly uploaded object
        assertEquals(1, fakeRemote.uploadedFiles.size)
        val newKey = fakeRemote.uploadedFiles.keys.first()
        assertTrue(fakeRemote.deletedPaths.contains(newKey))
        assertNull(fakePendingStore.records[testUserId])
    }

    @Test
    fun `commitRemote conflict cleans up new object and reports AVATAR_CONFLICT`() = runBlocking {
        fakeProfileRemote.updateAvatarShouldFail = DomainException(ErrorType.AVATAR_CONFLICT)

        val result = repository.commitRemote(testUserId, dummyDraft, oldAvatarUrl)

        assertTrue(result is ApiResponse.Failure)
        assertEquals(ErrorType.AVATAR_CONFLICT, (result as ApiResponse.Failure).error.type)

        val newKey = fakeRemote.uploadedFiles.keys.first()
        assertTrue(fakeRemote.deletedPaths.contains(newKey))
    }

    @Test
    fun `commitRemote timeout yields AVATAR_OUTCOME_UNKNOWN and does not delete new object`() = runBlocking {
        fakeProfileRemote.updateAvatarShouldFail = SocketTimeoutException("Read timed out")

        val result = repository.commitRemote(testUserId, dummyDraft, oldAvatarUrl)

        assertTrue(result is ApiResponse.Failure)
        assertEquals(ErrorType.AVATAR_OUTCOME_UNKNOWN, (result as ApiResponse.Failure).error.type)

        // New object must NOT be deleted yet because publish outcome is uncertain
        val newKey = fakeRemote.uploadedFiles.keys.first()
        assertTrue(!fakeRemote.deletedPaths.contains(newKey))

        // Pending record must be retained in PUBLISHING status
        val pending = fakePendingStore.records[testUserId]
        assertNotNull(pending)
        assertEquals(AvatarPendingStore.Status.PUBLISHING, pending!!.status)
    }

    @Test
    fun `removeRemote success clears avatar url and cleans up old object`() = runBlocking {
        val result = repository.removeRemote(testUserId, oldAvatarUrl)

        assertTrue(result is ApiResponse.Success)
        assertNull((result as ApiResponse.Success).data.avatarUrl)
        assertTrue(fakeRemote.deletedPaths.contains("$testUserId/old-photo.jpg"))
        assertNull(fakePendingStore.records[testUserId])
    }

    @Test
    fun `removeRemote timeout yields AVATAR_OUTCOME_UNKNOWN`() = runBlocking {
        fakeProfileRemote.updateAvatarShouldFail = SocketTimeoutException("Timed out")

        val result = repository.removeRemote(testUserId, oldAvatarUrl)

        assertTrue(result is ApiResponse.Failure)
        assertEquals(ErrorType.AVATAR_OUTCOME_UNKNOWN, (result as ApiResponse.Failure).error.type)
    }

    @Test
    fun `cleanupOldObject failure does not cause commitRemote to fail`() = runBlocking {
        fakeRemote.deleteShouldThrow = IOException("Cleanup storage failure")

        val result = repository.commitRemote(testUserId, dummyDraft, oldAvatarUrl)

        assertTrue("Save must still succeed even if cleanup throws", result is ApiResponse.Success)
    }

    @Test
    fun `non owner commitRemote is rejected with PERMISSION_DENIED`() = runBlocking {
        val result = repository.commitRemote(otherUserId, dummyDraft, oldAvatarUrl)

        assertTrue(result is ApiResponse.Failure)
        assertEquals(ErrorType.PERMISSION_DENIED, (result as ApiResponse.Failure).error.type)
        assertEquals(0, fakeRemote.uploadedFiles.size)
    }

    @Test
    fun `non owner removeRemote is rejected with PERMISSION_DENIED`() = runBlocking {
        val result = repository.removeRemote(otherUserId, oldAvatarUrl)

        assertTrue(result is ApiResponse.Failure)
        assertEquals(ErrorType.PERMISSION_DENIED, (result as ApiResponse.Failure).error.type)
    }

    @Test
    fun `reconcilePending confirms publish if profile matches new url`() = runBlocking {
        val newKey = "$testUserId/reconciled.jpg"
        val expectedNew = "https://example.supabase.co/storage/v1/object/public/avatars/$newKey"
        fakeProfileRemote.myProfile = Profile(
            id = testUserId,
            username = "testuser",
            displayName = "Test",
            bio = null,
            avatarUrl = expectedNew,
            createdAt = "2026-01-01"
        )
        fakePendingStore.records[testUserId] = AvatarPendingStore.Record(
            status = AvatarPendingStore.Status.PUBLISHING,
            objectPath = newKey,
            expectedUrl = oldAvatarUrl
        )

        repository.reconcilePending(testUserId)

        // Confirmed publish cleans old object
        assertTrue(fakeRemote.deletedPaths.contains("$testUserId/old-photo.jpg"))
        assertNull(fakePendingStore.records[testUserId])
    }

    @Test
    fun `reconcilePending cleans up unapplied new object if profile still has old url`() = runBlocking {
        val newKey = "$testUserId/unapplied.jpg"
        fakeProfileRemote.myProfile = Profile(
            id = testUserId,
            username = "testuser",
            displayName = "Test",
            bio = null,
            avatarUrl = oldAvatarUrl,
            createdAt = "2026-01-01"
        )
        fakePendingStore.records[testUserId] = AvatarPendingStore.Record(
            status = AvatarPendingStore.Status.PUBLISHING,
            objectPath = newKey,
            expectedUrl = oldAvatarUrl
        )

        repository.reconcilePending(testUserId)

        // Unapplied new object is cleaned up
        assertTrue(fakeRemote.deletedPaths.contains(newKey))
        assertNull(fakePendingStore.records[testUserId])
    }
}
