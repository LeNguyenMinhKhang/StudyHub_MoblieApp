package com.doan.studyhub.user.ui.group

import androidx.lifecycle.SavedStateHandle
import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.repository.GroupRepository
import com.doan.studyhub.core.data.repository.FriendshipRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

class GroupViewModelsTest {
    private val group = GroupSummary("group", "Nhóm", null, GroupSubject("subject", "Môn", true), GroupRole.OWNER, 2, "time", "time")
    private fun failure(type: ErrorType) = ApiResponse.Failure(ApiError(type), "test")
    private inner class Fake : GroupRepository() {
        var actor: String? = "actor"
        var sessionId = "session"
        var groupRead: ApiResponse<GroupDetail> = ApiResponse.Success(group, "test")
        var groupPages: suspend (GroupCursor?) -> ApiResponse<GroupPage<GroupSummary>> = { ApiResponse.Success(GroupPage(listOf(group)), "test") }
        var incomingRows = listOf(GroupInvitation("invite", "pending", "time", "group", "Nhóm", null, group.subject, GroupPeer("inviter", "Bạn")))
        var creates = mutableListOf<CreateGroupRequest>()
        var createResponse = CompletableDeferred<ApiResponse<GroupDetail>>()
        var response = CompletableDeferred<ApiResponse<GroupInvitationResult>>()
        var responses = 0
        var detailReads = 0
        var mutationResult: ApiResponse<GroupMutationResult> = ApiResponse.Success(GroupMutationResult("group", "removed"), "test")
        var updateResult: ApiResponse<GroupDetail> = failure(ErrorType.NETWORK_UNAVAILABLE)
        var invitationRead: suspend () -> ApiResponse<GroupPage<GroupInvitation>> = { ApiResponse.Success(GroupPage(incomingRows), "test") }
        var memberPages: suspend (String, GroupCursor?) -> ApiResponse<GroupPage<GroupMember>> = { id, _ ->
            ApiResponse.Success(GroupPage(if (id == "group") listOf(GroupMember("member", "Member", null, GroupRole.MEMBER, "time")) else emptyList()), "test")
        }
        override fun currentUserId() = actor
        override fun currentSessionKey() = sessionId
        override fun sessionChanges() = emptyFlow<String?>()
        override suspend fun detail(id: String): ApiResponse<GroupDetail> { detailReads++; return groupRead }
        override suspend fun groups(cursor: GroupCursor?, limit: Int) = groupPages(cursor)
        override suspend fun incoming(cursor: GroupCursor?, limit: Int) = ApiResponse.Success(GroupPage(incomingRows), "test")
        override suspend fun invitations(id: String, cursor: GroupCursor?, limit: Int) = invitationRead()
        override suspend fun members(id: String, cursor: GroupCursor?, limit: Int) = memberPages(id, cursor)
        override suspend fun create(request: CreateGroupRequest): ApiResponse<GroupDetail> { creates += request; return createResponse.await() }
        override suspend fun respond(id: String, action: GroupResponse): ApiResponse<GroupInvitationResult> { responses++; return response.await() }
        override suspend fun remove(id: String, target: String) = mutationResult
        override suspend fun leave(id: String) = mutationResult
        override suspend fun archive(id: String) = mutationResult
        override suspend fun update(id: String, name: String, description: String?) = updateResult
    }

    @Test fun creationRestoresDraftAndRequestIdAcrossFailureAndRecreation() = runBlocking {
        val repo = Fake()
        val saved = SavedStateHandle()
        val vm = CreateGroupViewModel(saved, repo, externalScope = this, loadSubjectList = { listOf(Subject("subject", "Môn")) })
        vm.edit("Nhóm 📚", "Mô tả", "subject")
        vm.submit(); vm.submit(); yield()
        assertEquals(1, repo.creates.size)
        val key = repo.creates.single().requestId
        repo.createResponse.complete(failure(ErrorType.NETWORK_UNAVAILABLE)); yield()
        assertEquals("Nhóm 📚", vm.state.value.name)
        assertNotNull(vm.state.value.error)
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val second = CreateGroupViewModel(restored, repo, externalScope = this, loadSubjectList = { listOf(Subject("subject", "Môn")) })
        repo.createResponse = CompletableDeferred()
        second.submit(); yield()
        assertEquals(key, repo.creates.last().requestId)
        repo.createResponse.complete(ApiResponse.Success(group, "test")); yield()
        assertEquals("group", second.state.value.created)
    }

    @Test fun subjectFailureRetainsFormAndRetryLoadsChoices() = runBlocking {
        val repo = Fake()
        var fail = true
        val vm = CreateGroupViewModel(SavedStateHandle(), repo, externalScope = this, loadSubjectList = {
            if (fail) throw java.io.IOException("offline") else listOf(Subject("subject", "Môn"))
        })
        vm.edit("Draft", "Text", "subject")
        vm.loadSubjects(); yield()
        assertEquals("Draft", vm.state.value.name)
        assertNotNull(vm.state.value.error)
        fail = false; vm.loadSubjects(); yield()
        assertEquals(1, vm.state.value.subjects.size)
        assertNull(vm.state.value.error)
    }

    @Test fun loadingFailureIsNotEmptySuccessAndPaginationDeduplicates() = runBlocking {
        val repo = Fake()
        repo.groupPages = { failure(ErrorType.NETWORK_UNAVAILABLE) }
        val vm = GroupBrowserViewModel(repo, externalScope = this)
        vm.refresh(); yield()
        assertFalse(vm.state.value.loaded)
        assertNotNull(vm.state.value.error)
        repo.groupPages = { cursor -> ApiResponse.Success(if (cursor == null)
            GroupPage(listOf(group), GroupCursor("at", "id")) else GroupPage(listOf(group, group.copy(id = "second"))), "test") }
        vm.refresh(); yield()
        assertTrue(vm.state.value.more)
        vm.more(); vm.more(); yield()
        assertEquals(listOf("group", "second"), vm.state.value.rows.map { it.id })
        assertFalse(vm.state.value.more)
    }

    @Test fun duplicateAcceptIsSuppressedAndStaleInvitationReloadsWithoutOpening() = runBlocking {
        val repo = Fake()
        val vm = GroupBrowserViewModel(repo, externalScope = this)
        vm.show(GroupSection.INCOMING); yield()
        val row = vm.state.value.rows.single()
        vm.act(row, GroupAction.ACCEPT); vm.act(row, GroupAction.ACCEPT); yield()
        assertEquals(1, repo.responses)
        repo.incomingRows = emptyList()
        repo.response.complete(failure(ErrorType.GROUP_INVITATION_UNAVAILABLE)); yield(); yield()
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.openGroup)
        assertNotNull(vm.state.value.actionError)
    }

    @Test fun successfulAcceptOpensOnlyAfterServerAcknowledgement() = runBlocking {
        val repo = Fake()
        val vm = GroupBrowserViewModel(repo, externalScope = this)
        vm.show(GroupSection.INCOMING); yield()
        vm.act(vm.state.value.rows.single(), GroupAction.ACCEPT); yield()
        assertNull(vm.state.value.openGroup)
        repo.incomingRows = emptyList()
        repo.response.complete(ApiResponse.Success(GroupInvitationResult("invite", "group", "accepted"), "test")); yield(); yield()
        assertEquals("group", vm.state.value.openGroup)
        vm.opened(); assertNull(vm.state.value.openGroup)
    }

    @Test fun roleRefreshRemovesOwnerActionsAndAccessLossClearsDetails() = runBlocking {
        val repo = Fake()
        val vm = GroupBrowserViewModel(repo, externalScope = this)
        vm.show(GroupSection.MEMBERS, "group"); yield()
        assertTrue(GroupAction.REMOVE in vm.state.value.rows.single().actions)
        repo.groupRead = ApiResponse.Success(group.copy(role = GroupRole.MEMBER), "test")
        vm.refresh(); yield()
        assertTrue(vm.state.value.rows.single().actions.isEmpty())
        repo.groupRead = failure(ErrorType.GROUP_UNAVAILABLE)
        vm.refresh(); yield()
        assertTrue(vm.state.value.inaccessible)
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.detail)
    }

    @Test fun accountSwitchDuringLoadDiscardsOldDataAndSameAccountLoginClearsDraft() = runBlocking {
        val repo = Fake()
        val pending = CompletableDeferred<ApiResponse<GroupPage<GroupSummary>>>()
        repo.groupPages = { pending.await() }
        val vm = GroupBrowserViewModel(repo, externalScope = this)
        vm.refresh(); yield()
        repo.actor = "other"
        pending.complete(ApiResponse.Success(GroupPage(listOf(group)), "test")); yield()
        assertTrue(vm.state.value.rows.isEmpty())
        val form = CreateGroupViewModel(SavedStateHandle(), repo, externalScope = this)
        form.edit("Private draft", "text", "subject")
        repo.sessionId = "new-login"
        form.edit("", "", null)
        assertEquals("", form.state.value.name)
        assertNull(form.state.value.subjectId)
    }

    @Test fun detailAccessFailureClosesAndErasesPrivateData() = runBlocking {
        val repo = Fake()
        val vm = GroupDetailViewModel(repo, this)
        vm.show("group"); yield()
        assertNotNull(vm.state.value.detail)
        repo.groupRead = failure(ErrorType.GROUP_UNAVAILABLE)
        vm.refresh(); yield()
        assertNull(vm.state.value.detail)
        assertTrue(vm.state.value.closed)
    }

    @Test fun switchingOwnerSectionsRetainsRoleWhileLoadingAndRevocationClearsPendingData() = runBlocking {
        val repo = Fake()
        val vm = GroupBrowserViewModel(repo, externalScope = this)
        vm.show(GroupSection.MEMBERS, "group"); yield()
        val pending = CompletableDeferred<ApiResponse<GroupPage<GroupInvitation>>>()
        repo.invitationRead = { pending.await() }
        vm.show(GroupSection.PENDING, "group")
        assertEquals(GroupRole.OWNER, vm.state.value.detail?.role)
        assertEquals(GroupSection.PENDING, vm.state.value.section)
        yield()
        pending.complete(ApiResponse.Success(GroupPage(repo.incomingRows), "test")); yield()
        assertEquals("invite", vm.state.value.rows.single().id)
        repo.groupRead = ApiResponse.Success(group.copy(role = GroupRole.MEMBER), "test")
        repo.invitationRead = { fail("Restricted invitations must not load after known ownership loss"); failure(ErrorType.PERMISSION_DENIED) }
        vm.refresh(); yield(); yield()
        assertEquals(GroupSection.MEMBERS, vm.state.value.section)
        assertEquals(GroupRole.MEMBER, vm.state.value.detail?.role)
        assertFalse(vm.state.value.rows.any { it.id == "invite" })
    }

    @Test fun ownershipLostBetweenDetailAndInvitationReadClearsRestrictedRows() = runBlocking {
        val repo = Fake()
        val vm = GroupBrowserViewModel(repo, externalScope = this)
        vm.show(GroupSection.PENDING, "group"); yield()
        assertEquals("invite", vm.state.value.rows.single().id)
        repo.invitationRead = {
            repo.groupRead = ApiResponse.Success(group.copy(role = GroupRole.MEMBER), "test")
            failure(ErrorType.PERMISSION_DENIED)
        }
        vm.refresh(); yield(); yield()
        assertEquals(GroupSection.MEMBERS, vm.state.value.section)
        assertEquals(GroupRole.MEMBER, vm.state.value.detail?.role)
        assertFalse(vm.state.value.rows.any { it.id == "invite" })
        assertTrue(vm.state.value.rows.all { it.actions.isEmpty() })
    }

    @Test fun clearingSubjectPreventsSubmittingPreviouslySelectedSubject() = runBlocking {
        val repo = Fake()
        val vm = CreateGroupViewModel(SavedStateHandle(), repo, externalScope = this)
        vm.edit("Group", "", "subject")
        vm.edit("Group", "", null)
        vm.submit(); yield()
        assertNull(vm.state.value.subjectId)
        assertTrue(repo.creates.isEmpty())
        assertNotNull(vm.state.value.error)
    }

    @Test fun bannedAccountRefreshImmediatelyClearsDetail() = runBlocking {
        val repo = Fake()
        val vm = GroupDetailViewModel(repo, this)
        vm.show("group"); yield()
        repo.groupRead = failure(ErrorType.ACCOUNT_BANNED)
        vm.refresh(); yield()
        assertTrue(vm.state.value.closed)
        assertNull(vm.state.value.detail)
    }

    @Test fun mutationAccessLossClearsPrivateDataEvenIfNextReadWouldFailOffline() = runBlocking {
        val repo = Fake()
        val detail = GroupDetailViewModel(repo, this)
        detail.show("group"); yield()
        repo.updateResult = failure(ErrorType.GROUP_UNAVAILABLE)
        repo.groupRead = failure(ErrorType.NETWORK_UNAVAILABLE)
        val readsBefore = repo.detailReads
        detail.save("Draft", "Text"); yield()
        assertTrue(detail.state.value.closed)
        assertNull(detail.state.value.detail)
        assertEquals(readsBefore, repo.detailReads)

        repo.groupRead = ApiResponse.Success(group, "test")
        val members = GroupBrowserViewModel(repo, externalScope = this)
        members.show(GroupSection.MEMBERS, "group"); yield()
        repo.mutationResult = failure(ErrorType.ACCOUNT_BANNED)
        repo.groupRead = failure(ErrorType.NETWORK_UNAVAILABLE)
        members.act(members.state.value.rows.single(), GroupAction.REMOVE); yield()
        assertTrue(members.state.value.inaccessible)
        assertTrue(members.state.value.rows.isEmpty())
        assertNull(members.state.value.detail)
        assertNull(members.state.value.busy)
    }

    @Test fun friendPickerDoesNotReuseOtherGroupsKnownMembers() = runBlocking {
        val repo = Fake()
        val friends = object : FriendshipRepository() {
            override suspend fun list(kind: FriendshipListKind, cursor: FriendshipCursor?, limit: Int) =
                ApiResponse.Success(FriendshipPage(listOf(FriendshipListItem("friendship", "time", "accepted", "outgoing",
                    FriendshipPeer("member", "friend", "Friend")))), "test")
        }
        val vm = GroupBrowserViewModel(repo, friends, this)
        vm.show(GroupSection.MEMBERS, "group"); yield()
        vm.show(GroupSection.FRIENDS, "group"); yield()
        assertTrue(vm.state.value.rows.isEmpty())
        vm.show(GroupSection.FRIENDS, "other-group"); yield()
        assertEquals("member", vm.state.value.rows.single().id)
    }

    @Test fun restoredDraftFromPreviousLoginIsClearedEvenForSameUser() = runBlocking {
        val repo = Fake()
        val saved = SavedStateHandle()
        CreateGroupViewModel(saved, repo, externalScope = this).edit("Private", "Draft", "subject")
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        repo.sessionId = "another-login"
        val recreated = CreateGroupViewModel(restored, repo, externalScope = this)
        assertEquals("", recreated.state.value.name)
        assertEquals("", recreated.state.value.description)
        assertNull(recreated.state.value.subjectId)
    }

    @Test fun failedDetailSaveAndRefreshKeepDraftUntilOwnershipIsLost() = runBlocking {
        val repo = Fake()
        val vm = GroupDetailViewModel(repo, this)
        vm.show("group"); yield()
        vm.edit("Unsaved name", "Unsaved text")
        repo.groupRead = ApiResponse.Success(group.copy(name = "Changed elsewhere", updatedAt = "later"), "test")
        vm.save("Unsaved name", "Unsaved text"); yield()
        assertEquals("Unsaved name", vm.state.value.draftName)
        assertEquals("Unsaved text", vm.state.value.draftDescription)
        assertTrue(vm.state.value.dirty)
        assertNotNull(vm.state.value.error)
        vm.refresh(); yield()
        assertEquals("Unsaved name", vm.state.value.draftName)
        repo.groupRead = ApiResponse.Success(group.copy(role = GroupRole.MEMBER), "test")
        vm.refresh(); yield()
        assertEquals(group.name, vm.state.value.draftName)
        assertFalse(vm.state.value.dirty)
    }

    @Test fun leaveAndArchiveClearDetailOnlyAfterSuccess() = runBlocking {
        val repo = Fake()
        repo.groupRead = ApiResponse.Success(group.copy(role = GroupRole.MEMBER), "test")
        val vm = GroupDetailViewModel(repo, this)
        vm.show("group"); yield()
        repo.mutationResult = failure(ErrorType.NETWORK_UNAVAILABLE)
        vm.leave(); yield()
        assertFalse(vm.state.value.closed)
        assertNotNull(vm.state.value.detail)
        assertNotNull(vm.state.value.error)
        repo.mutationResult = ApiResponse.Success(GroupMutationResult("group", "left"), "test")
        vm.leave(); yield()
        assertTrue(vm.state.value.closed)
        assertNull(vm.state.value.detail)

        repo.groupRead = ApiResponse.Success(group, "test")
        val owner = GroupDetailViewModel(repo, this)
        owner.show("group"); yield()
        owner.archive(); yield()
        assertTrue(owner.state.value.closed)
        assertNull(owner.state.value.detail)
    }

    @Test fun friendPaginationContinuesAfterFilteredPageAndDeduplicates() = runBlocking {
        val repo = Fake()
        val cursors = mutableListOf<FriendshipCursor?>()
        val next = FriendshipCursor("time", "cursor")
        fun item(id: String) = FriendshipListItem(id, "time", "accepted", "outgoing", FriendshipPeer(id, id, id))
        val friends = object : FriendshipRepository() {
            override suspend fun list(kind: FriendshipListKind, cursor: FriendshipCursor?, limit: Int): ApiResponse<FriendshipPage> {
                assertEquals(FriendshipListKind.FRIENDS, kind)
                cursors += cursor
                return ApiResponse.Success(if (cursor == null) FriendshipPage(listOf(item("member")), next)
                    else FriendshipPage(listOf(item("friend"), item("friend"))), "test")
            }
        }
        val vm = GroupBrowserViewModel(repo, friends, this)
        vm.show(GroupSection.MEMBERS, "group"); yield()
        vm.show(GroupSection.FRIENDS, "group"); yield()
        assertTrue(vm.state.value.rows.isEmpty())
        assertTrue(vm.state.value.more)
        vm.more(); vm.more(); yield()
        assertEquals(listOf(null, next), cursors)
        assertEquals(listOf("friend"), vm.state.value.rows.map { it.id })
        assertFalse(vm.state.value.more)
    }

    @Test fun memberPaginationRevokesOwnerActionsFromEveryPage() = runBlocking {
        val repo = Fake()
        repo.memberPages = { _, cursor -> ApiResponse.Success(GroupPage(
            listOf(GroupMember(if (cursor == null) "first" else "second", "Member", null, GroupRole.MEMBER, "time")),
            if (cursor == null) GroupCursor("time", "first") else null), "test") }
        val vm = GroupBrowserViewModel(repo, externalScope = this)
        vm.show(GroupSection.MEMBERS, "group"); yield()
        assertTrue(GroupAction.REMOVE in vm.state.value.rows.single().actions)
        repo.groupRead = ApiResponse.Success(group.copy(role = GroupRole.MEMBER), "test")
        vm.more(); yield()
        assertEquals(2, vm.state.value.rows.size)
        assertTrue(vm.state.value.rows.all { it.actions.isEmpty() })
    }

    @Test fun failedNextPageStillRemovesActionsAfterKnownOwnershipLoss() = runBlocking {
        val repo = Fake()
        repo.memberPages = { _, cursor -> if (cursor == null) ApiResponse.Success(GroupPage(
            listOf(GroupMember("member", "Member", null, GroupRole.MEMBER, "time")), GroupCursor("time", "member")), "test")
            else failure(ErrorType.NETWORK_UNAVAILABLE) }
        val vm = GroupBrowserViewModel(repo, externalScope = this)
        vm.show(GroupSection.MEMBERS, "group"); yield()
        repo.groupRead = ApiResponse.Success(group.copy(role = GroupRole.MEMBER), "test")
        vm.more(); yield()
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.rows.single().actions.isEmpty())
    }

    @Test fun refreshingFriendsReconcilesDeclinedInvitationsAndDepartedMembers() = runBlocking {
        val repo = Fake()
        repo.incomingRows = listOf(repo.incomingRows.single().copy(invitee = GroupPeer("pending", "Pending")))
        val friends = object : FriendshipRepository() {
            override suspend fun list(kind: FriendshipListKind, cursor: FriendshipCursor?, limit: Int) =
                ApiResponse.Success(FriendshipPage(listOf("member", "pending").map { id ->
                    FriendshipListItem(id, "time", "accepted", "outgoing", FriendshipPeer(id, id, id))
                }), "test")
        }
        val vm = GroupBrowserViewModel(repo, friends, this)
        vm.show(GroupSection.FRIENDS, "group"); yield()
        assertTrue(vm.state.value.rows.isEmpty())
        repo.incomingRows = emptyList()
        repo.memberPages = { _, _ -> ApiResponse.Success(GroupPage(emptyList()), "test") }
        vm.refresh(); yield()
        assertEquals(listOf("member", "pending"), vm.state.value.rows.map { it.id })
    }
}
