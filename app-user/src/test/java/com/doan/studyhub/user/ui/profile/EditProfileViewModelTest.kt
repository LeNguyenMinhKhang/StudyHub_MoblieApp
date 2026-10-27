package com.doan.studyhub.user.ui.profile

import android.app.Application
import com.doan.studyhub.common.ApiError
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorLogger
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.local.AvatarPendingStore
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.model.UpdateProfileRequest
import com.doan.studyhub.core.data.remote.AvatarRemoteDataSource
import com.doan.studyhub.core.data.remote.ProfileRemoteDataSource
import com.doan.studyhub.core.data.repository.AvatarRepository
import com.doan.studyhub.core.data.repository.ProfileRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditProfileViewModelTest {

    private val testUserId = "11111111-1111-1111-1111-111111111111"
    private val otherUserId = "22222222-2222-2222-2222-222222222222"
    private var currentSessionUser: String? = testUserId

    private lateinit var fakeProfileRepo: FakeProfileRepository
    private lateinit var fakeAvatarRepo: AvatarRepository
    private lateinit var fakeAvatarRemote: FakeAvatarRemoteDataSource
    private lateinit var fakeProfileRemote: FakeProfileRemoteDataSource
    private lateinit var fakePendingStore: FakePendingStore
    private lateinit var dummyDraft: File
    private lateinit var viewModel: EditProfileViewModel

    class FakeProfileRepository(
        var profile: Profile,
        var updateShouldFail: Boolean = false,
        var currentUserIdProvider: () -> String? = { null }
    ) : ProfileRepository() {
        var lastUpdateRequest: UpdateProfileRequest? = null

        override fun currentUserId(): String? = currentUserIdProvider()

        override suspend fun getMine(): ApiResponse<Profile> =
            ApiResponse.Success(profile, "trace-1")

        override suspend fun updateMine(
            request: UpdateProfileRequest,
            originalUsername: String?
        ): ApiResponse<Profile> {
            lastUpdateRequest = request
            if (updateShouldFail) {
                return ApiResponse.Failure(
                    error = ApiError(type = ErrorType.UNKNOWN_ERROR),
                    traceId = "trace-err"
                )
            }
            profile = profile.copy(
                username = request.username,
                displayName = request.displayName,
                bio = request.bio
            )
            return ApiResponse.Success(profile, "trace-ok")
        }
    }

    class FakeAvatarRemoteDataSource : AvatarRemoteDataSource() {
        val uploadedFiles = mutableMapOf<String, File>()
        override suspend fun upload(objectPath: String, file: File) {
            uploadedFiles[objectPath] = file
        }
        override suspend fun deleteIfUnreferenced(objectPath: String) {}
        override fun publicUrl(objectPath: String): String =
            "https://example.supabase.co/storage/v1/object/public/avatars/$objectPath"
    }

    class FakeProfileRemoteDataSource : ProfileRemoteDataSource() {
        var updateAvatarShouldFailType: ErrorType? = null

        override suspend fun getMine(): Profile? = null

        override suspend fun updateMyAvatar(objectPath: String?, expectedAvatarUrl: String?): Profile? {
            updateAvatarShouldFailType?.let {
                throw com.doan.studyhub.common.DomainException(it)
            }
            return Profile(
                id = "11111111-1111-1111-1111-111111111111",
                username = "testuser",
                displayName = "Test User",
                bio = "bio",
                avatarUrl = objectPath?.let { "https://example.supabase.co/storage/v1/object/public/avatars/$it" },
                createdAt = "2026-01-01"
            )
        }
    }

    class FakePendingStore : AvatarPendingStore() {
        override suspend fun save(userId: String, record: Record) {}
        override suspend fun load(userId: String): Record? = null
        override suspend fun clear(userId: String) {}
    }

    @Before
    fun setup() {
        currentSessionUser = testUserId
        dummyDraft = File.createTempFile("draft", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            deleteOnExit()
        }
        val initialProfile = Profile(
            id = testUserId,
            username = "original_user",
            displayName = "Original Name",
            bio = "Original Bio",
            avatarUrl = "https://example.supabase.co/storage/v1/object/public/avatars/$testUserId/old.jpg",
            createdAt = "2026-01-01"
        )
        fakeProfileRepo = FakeProfileRepository(initialProfile, currentUserIdProvider = { currentSessionUser })
        fakeAvatarRemote = FakeAvatarRemoteDataSource()
        fakeProfileRemote = FakeProfileRemoteDataSource()
        fakePendingStore = FakePendingStore()
        fakeAvatarRepo = AvatarRepository(
            context = null,
            local = null,
            remote = fakeAvatarRemote,
            profileRemote = fakeProfileRemote,
            pendingStore = fakePendingStore,
            api = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> }),
            ownerCheck = { it == currentSessionUser }
        )
        viewModel = EditProfileViewModel(
            application = Application(),
            profiles = fakeProfileRepo,
            avatars = fakeAvatarRepo,
            externalScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `load initializes ui state with profile values`() = runBlocking {
        viewModel.load()
        val state = viewModel.uiState.value
        assertEquals("original_user", state.username)
        assertEquals("Original Name", state.displayName)
        assertEquals("Original Bio", state.bio)
        assertFalse(state.isDirty)
        assertFalse(state.isLoading)
    }

    @Test
    fun `avatar-only save commits avatar and does not update text`() = runBlocking {
        viewModel.load()
        viewModel.useRetainedLocalAvatar(dummyDraft)

        val stateBefore = viewModel.uiState.value
        assertTrue(stateBefore.isDirty)
        assertFalse(stateBefore.textDirty)
        assertEquals(PendingAvatar.REPLACE, stateBefore.avatarAction)

        viewModel.save()

        val stateAfter = viewModel.uiState.value
        assertNull("No text update should be sent", fakeProfileRepo.lastUpdateRequest)
        assertTrue("Completed should be true", stateAfter.completed)
        assertNotNull(stateAfter.updatedProfile?.avatarUrl)
        assertEquals(PendingAvatar.KEEP, stateAfter.avatarAction)
    }

    @Test
    fun `text failure blocks avatar commit and preserves draft`() = runBlocking {
        fakeProfileRepo.updateShouldFail = true
        viewModel.load()
        viewModel.setDisplayName("New Name")
        viewModel.useRetainedLocalAvatar(dummyDraft)

        viewModel.save()

        val state = viewModel.uiState.value
        assertFalse("Must not complete on text failure", state.completed)
        assertFalse("Save should end", state.isSaving)
        assertEquals(ErrorType.UNKNOWN_ERROR.userMessage, state.errorMessage)
        assertFalse("Partial save must be false if text failed", state.partialSave)
        assertEquals(PendingAvatar.REPLACE, state.avatarAction)
        assertEquals(0, fakeAvatarRemote.uploadedFiles.size)
    }

    @Test
    fun `partial save when text succeeds but avatar fails allows retry`() = runBlocking {
        fakeProfileRemote.updateAvatarShouldFailType = ErrorType.AVATAR_UPLOAD_FAILED
        viewModel.load()
        viewModel.setDisplayName("New Name")
        viewModel.useRetainedLocalAvatar(dummyDraft)

        viewModel.save()

        val state = viewModel.uiState.value
        assertFalse(state.completed)
        assertFalse(state.isSaving)
        assertTrue("Partial save must be true", state.partialSave)
        assertTrue("Can retry avatar must be true", state.canRetryAvatar)
        assertEquals(dummyDraft, state.avatarDraft)

        // Now retry with succeeding avatar remote
        fakeProfileRemote.updateAvatarShouldFailType = null
        viewModel.retryAvatar()

        val stateAfterRetry = viewModel.uiState.value
        assertTrue("Should complete after retry", stateAfterRetry.completed)
        assertFalse("Partial save cleared", stateAfterRetry.partialSave)
        assertNotNull(stateAfterRetry.updatedProfile)
    }

    @Test
    fun `avatar conflict flags avatarConflict and disables retry until refresh`() = runBlocking {
        fakeProfileRemote.updateAvatarShouldFailType = ErrorType.AVATAR_CONFLICT
        viewModel.load()
        viewModel.useRetainedLocalAvatar(dummyDraft)

        viewModel.save()

        val state = viewModel.uiState.value
        assertFalse(state.completed)
        assertTrue(state.avatarConflict)
        assertFalse("Retry is disabled on conflict", state.canRetryAvatar)
    }

    @Test
    fun `avatar timeout flags avatarOutcomeUnknown`() = runBlocking {
        fakeProfileRemote.updateAvatarShouldFailType = ErrorType.AVATAR_OUTCOME_UNKNOWN
        viewModel.load()
        viewModel.useRetainedLocalAvatar(dummyDraft)

        viewModel.save()

        val state = viewModel.uiState.value
        assertFalse(state.completed)
        assertTrue(state.avatarOutcomeUnknown)
    }

    @Test
    fun `account switch rejects save and sets session error`() = runBlocking {
        viewModel.load()
        viewModel.setDisplayName("New Name")

        // User switches account before save
        currentSessionUser = otherUserId

        viewModel.save()

        val state = viewModel.uiState.value
        assertFalse(state.completed)
        assertEquals("Phiên đăng nhập đã thay đổi.", state.errorMessage)
        assertNull(fakeProfileRepo.lastUpdateRequest)
    }

    @Test
    fun `removeAvatar sets PendingAvatar REMOVE and save removes remote avatar`() = runBlocking {
        viewModel.load()
        viewModel.removeAvatar()

        val stateBefore = viewModel.uiState.value
        assertEquals(PendingAvatar.REMOVE, stateBefore.avatarAction)

        viewModel.save()

        val stateAfter = viewModel.uiState.value
        assertTrue(stateAfter.completed)
        assertNull(stateAfter.updatedProfile?.avatarUrl)
    }
}
