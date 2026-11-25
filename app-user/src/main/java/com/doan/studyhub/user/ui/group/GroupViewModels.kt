package com.doan.studyhub.user.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class GroupSection { GROUPS, INCOMING, MEMBERS, PENDING, FRIENDS }
enum class GroupAction { OPEN, ACCEPT, DECLINE, INVITE, REVOKE, REMOVE, TRANSFER }
private fun ErrorType.revokesGroupAccess() = this in setOf(
    ErrorType.GROUP_UNAVAILABLE, ErrorType.UNAUTHENTICATED,
    ErrorType.GROUP_ACCOUNT_UNAVAILABLE, ErrorType.ACCOUNT_BANNED
)
data class GroupRow(val id: String, val title: String, val subtitle: String, val actions: List<GroupAction>)
data class GroupBrowseState(
    val section: GroupSection = GroupSection.GROUPS,
    val rows: List<GroupRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val more: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
    val busy: String? = null,
    val detail: GroupDetail? = null,
    val openGroup: String? = null,
    val completedAction: GroupAction? = null,
    val inaccessible: Boolean = false
)

open class GroupBrowserViewModel @JvmOverloads constructor(
    private val groups: GroupRepository = GroupRepository(),
    private val friends: FriendshipRepository = FriendshipRepository(),
    private val externalScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(GroupBrowseState())
    val state = mutable.asStateFlow()
    private var groupId: String? = null
    private var identity = groups.currentUserId() to groups.currentSessionKey()
    private var generation = 0
    private var job: Job? = null
    private var cursor: GroupCursor? = null
    private var friendCursor: FriendshipCursor? = null
    private val knownMembers = mutableSetOf<String>()
    private val knownPending = mutableSetOf<String>()

    init { scope.launch { groups.sessionChanges().collect { syncSession() } } }
    private fun syncSession(): Boolean {
        val current = groups.currentUserId() to groups.currentSessionKey()
        if (identity != current) {
            identity = current; generation++; job?.cancel(); cursor = null; friendCursor = null
            knownMembers.clear(); knownPending.clear()
            mutable.value = GroupBrowseState(section = mutable.value.section, inaccessible = groupId != null)
        }
        return current.first != null
    }
    fun show(section: GroupSection, id: String? = null) {
        syncSession()
        if (mutable.value.section == section && groupId == id && mutable.value.loaded) return
        if (groupId != id) { knownMembers.clear(); knownPending.clear() }
        val retainedDetail = if (groupId == id) mutable.value.detail else null
        job?.cancel(); generation++; groupId = id
        mutable.value = GroupBrowseState(section = section, detail = retainedDetail)
        refresh()
    }
    fun refresh() = load(false)
    fun more() { if (mutable.value.more) load(true) }
    fun opened() { mutable.value = mutable.value.copy(openGroup = null) }
    fun acknowledged() { mutable.value = mutable.value.copy(completedAction = null) }

    private fun load(append: Boolean) {
        if (!syncSession() || mutable.value.loading || mutable.value.busy != null) return
        val section = mutable.value.section
        val request = ++generation
        if (!append) { cursor = null; friendCursor = null }
        mutable.value = mutable.value.copy(loading = true, error = null)
        job = scope.launch {
            var detail = mutable.value.detail
            groupId?.let { id ->
                when (val result = groups.detail(id)) {
                    is ApiResponse.Success -> detail = result.data
                    is ApiResponse.Failure -> { finishFailure(result, request); return@launch }
                }
            }
            if (!valid(request)) return@launch
            mutable.value = mutable.value.copy(detail = detail,
                rows = if (section == GroupSection.MEMBERS && detail?.role != GroupRole.OWNER)
                    mutable.value.rows.map { it.copy(actions = emptyList()) } else mutable.value.rows)
            // Pending invitations stay owner-only; any member may invite their own friends.
            if (section == GroupSection.PENDING && detail?.role != GroupRole.OWNER) {
                knownPending.clear()
                mutable.value = GroupBrowseState(section = GroupSection.MEMBERS, detail = detail)
                refresh()
                return@launch
            }
            val rows: List<GroupRow>
            var more = false
            when (section) {
                GroupSection.GROUPS -> when (val result = groups.groups(cursor)) {
                    is ApiResponse.Failure -> { finishFailure(result, request); return@launch }
                    is ApiResponse.Success -> {
                        if (!valid(request)) return@launch
                        cursor = result.data.nextCursor; more = cursor != null
                        rows = result.data.items.map { GroupRow(it.id, it.name, it.subject?.name.orEmpty(), listOf(GroupAction.OPEN)) }
                    }
                }
                GroupSection.INCOMING -> when (val result = groups.incoming(cursor)) {
                    is ApiResponse.Failure -> { finishFailure(result, request); return@launch }
                    is ApiResponse.Success -> {
                        if (!valid(request)) return@launch
                        cursor = result.data.nextCursor; more = cursor != null
                        rows = result.data.items.map { GroupRow(it.id, it.groupName,
                            listOfNotNull(it.subject?.name, it.description, it.inviter.displayName).joinToString("\n"), listOf(GroupAction.ACCEPT, GroupAction.DECLINE)) }
                    }
                }
                GroupSection.MEMBERS -> when (val result = groups.members(requireNotNull(groupId), cursor)) {
                    is ApiResponse.Failure -> { finishFailure(result, request); return@launch }
                    is ApiResponse.Success -> {
                        if (!valid(request)) return@launch
                        if (!append) knownMembers.clear()
                        knownMembers += result.data.items.map { it.userId }
                        cursor = result.data.nextCursor; more = cursor != null
                        rows = result.data.items.map {
                            GroupRow(it.userId, it.displayName, it.role.name,
                                if (detail?.role == GroupRole.OWNER && it.role != GroupRole.OWNER) listOf(GroupAction.REMOVE, GroupAction.TRANSFER) else emptyList())
                        }
                    }
                }
                GroupSection.PENDING -> when (val result = groups.invitations(requireNotNull(groupId), cursor)) {
                    is ApiResponse.Failure -> { finishFailure(result, request); return@launch }
                    is ApiResponse.Success -> {
                        if (!valid(request)) return@launch
                        if (!append) knownPending.clear()
                        knownPending += result.data.items.mapNotNull { it.invitee?.id }
                        cursor = result.data.nextCursor; more = cursor != null
                        rows = result.data.items.map { GroupRow(it.id, it.invitee?.displayName.orEmpty(), "", listOf(GroupAction.REVOKE)) }
                    }
                }
                GroupSection.FRIENDS -> {
                    if (!append) {
                        // Reconcile known eligibility on refresh; the invite RPC checks the full group.
                        when (val result = groups.members(requireNotNull(groupId), limit = 50)) {
                            is ApiResponse.Failure -> { finishFailure(result, request); return@launch }
                            is ApiResponse.Success -> {
                                if (!valid(request)) return@launch
                                knownMembers.clear()
                                knownMembers += result.data.items.map { it.userId }
                            }
                        }
                        // Only the owner can read the pending list; for other members the server
                        // returns the existing invitation when someone was already invited.
                        if (detail?.role == GroupRole.OWNER) {
                            when (val result = groups.invitations(requireNotNull(groupId), limit = 50)) {
                                is ApiResponse.Failure -> { finishFailure(result, request); return@launch }
                                is ApiResponse.Success -> {
                                    if (!valid(request)) return@launch
                                    knownPending.clear()
                                    knownPending += result.data.items.mapNotNull { it.invitee?.id }
                                }
                            }
                        }
                    }
                    when (val result = friends.list(FriendshipListKind.FRIENDS, friendCursor)) {
                        is ApiResponse.Failure -> { finishFailure(result, request); return@launch }
                        is ApiResponse.Success -> {
                            if (!valid(request)) return@launch
                            friendCursor = result.data.nextCursor; more = friendCursor != null
                            rows = result.data.items.filter { it.peer.id !in knownMembers && it.peer.id !in knownPending }
                                .map { GroupRow(it.peer.id, it.peer.displayName, it.peer.username, listOf(GroupAction.INVITE)) }
                        }
                    }
                }
            }
            if (!valid(request)) return@launch
            val combined = (if (append) mutable.value.rows + rows else rows).distinctBy { it.id }
            val permittedRows = if (section == GroupSection.MEMBERS) combined.map { row ->
                row.copy(actions = if (detail?.role == GroupRole.OWNER && row.subtitle == GroupRole.MEMBER.name)
                    listOf(GroupAction.REMOVE, GroupAction.TRANSFER) else emptyList())
            } else combined
            mutable.value = mutable.value.copy(rows = permittedRows,
                loading = false, loaded = true, more = more, detail = detail)
        }
    }

    fun act(row: GroupRow, action: GroupAction) {
        if (!syncSession() || mutable.value.loading || mutable.value.busy != null || action !in row.actions) return
        if (action == GroupAction.OPEN) { mutable.value = mutable.value.copy(openGroup = row.id); return }
        val request = ++generation
        mutable.value = mutable.value.copy(busy = row.id, actionError = null, completedAction = null)
        job = scope.launch {
            val result = when (action) {
                GroupAction.ACCEPT -> groups.respond(row.id, GroupResponse.ACCEPT)
                GroupAction.DECLINE -> groups.respond(row.id, GroupResponse.DECLINE)
                GroupAction.INVITE -> groups.invite(requireNotNull(groupId), row.id)
                GroupAction.REVOKE -> groups.revoke(row.id)
                GroupAction.REMOVE -> groups.remove(requireNotNull(groupId), row.id)
                GroupAction.TRANSFER -> groups.transfer(requireNotNull(groupId), row.id)
                GroupAction.OPEN -> return@launch
            }
            if (!valid(request)) return@launch
            if (result is ApiResponse.Failure && result.error.type.revokesGroupAccess()) {
                finishFailure(result, request)
                return@launch
            }
            val error = (result as? ApiResponse.Failure)?.error?.message
            val open = if (action == GroupAction.ACCEPT && result is ApiResponse.Success) (result.data as GroupInvitationResult).groupId else null
            if (action == GroupAction.INVITE && result is ApiResponse.Success) knownPending += row.id
            if (action == GroupAction.REMOVE && result is ApiResponse.Success) knownMembers -= row.id
            mutable.value = mutable.value.copy(busy = null, actionError = error, openGroup = open,
                completedAction = if (result is ApiResponse.Success) action else null)
            // Reload permissions and the current pending list even for stale mutations.
            refresh()
        }
    }
    private fun valid(request: Int) = syncSession() && request == generation
    private fun finishFailure(result: ApiResponse.Failure, request: Int) {
        if (!valid(request)) return
        if (result.error.type == ErrorType.PERMISSION_DENIED &&
            mutable.value.section in setOf(GroupSection.PENDING, GroupSection.FRIENDS)) {
            knownPending.clear(); cursor = null; friendCursor = null
            mutable.value = GroupBrowseState(section = GroupSection.MEMBERS)
            refresh()
            return
        }
        val lost = result.error.type.revokesGroupAccess()
        if (lost) { knownMembers.clear(); knownPending.clear(); cursor = null; friendCursor = null }
        mutable.value = mutable.value.copy(loading = false, busy = null, error = result.error.message,
            actionError = if (lost) null else mutable.value.actionError,
            openGroup = if (lost) null else mutable.value.openGroup,
            rows = if (lost) emptyList() else mutable.value.rows, detail = if (lost) null else mutable.value.detail,
            more = if (lost) false else mutable.value.more,
            inaccessible = lost && groupId != null)
    }
}

class GroupMembersViewModel : GroupBrowserViewModel()

data class CreateGroupState(val name: String = "", val description: String = "", val subjectId: String? = null,
    val subjects: List<Subject> = emptyList(), val loading: Boolean = false, val busy: Boolean = false,
    val error: String? = null, val created: String? = null)

class CreateGroupViewModel @JvmOverloads constructor(
    private val saved: SavedStateHandle,
    private val groups: GroupRepository = GroupRepository(),
    private val externalScope: CoroutineScope? = null,
    private val loadSubjectList: suspend () -> List<Subject> = { SubjectRepository().getActiveSubjects() }
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(CreateGroupState(saved["name"] ?: "", saved["description"] ?: "", saved["subject"]))
    val state = mutable.asStateFlow()
    private var identity = groups.currentUserId() to groups.currentSessionKey()
    private var generation = 0
    private var job: Job? = null
    init {
        if (saved.get<String>("owner") != identity.first || saved.get<String>("ownerSession") != identity.second) clearDraft()
        scope.launch { groups.sessionChanges().collect { syncSession() } }
    }
    private fun clearDraft() {
        listOf("name","description","subject","request","payload").forEach { saved.remove<Any>(it) }
        saved["owner"] = identity.first
        saved["ownerSession"] = identity.second
        mutable.value = CreateGroupState()
    }
    private fun syncSession(): Boolean {
        val current = groups.currentUserId() to groups.currentSessionKey()
        if (identity != current) { identity = current; generation++; job?.cancel(); clearDraft() }
        return current.first != null
    }
    fun edit(name: String, description: String, subjectId: String?) {
        if (!syncSession() || mutable.value.busy) return
        saved["name"] = name; saved["description"] = description; saved["subject"] = subjectId
        mutable.value = mutable.value.copy(name = name, description = description, subjectId = subjectId, created = null)
    }
    fun loadSubjects() {
        if (!syncSession() || mutable.value.loading || mutable.value.busy) return
        val request = ++generation
        mutable.value = mutable.value.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val available = loadSubjectList()
                if (!syncSession() || request != generation) return@launch
                mutable.value = mutable.value.copy(subjects = available, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (syncSession() && request == generation) mutable.value = mutable.value.copy(loading = false, error = ErrorType.NETWORK_UNAVAILABLE.userMessage)
            }
        }
    }
    fun submit() {
        // The subject is optional: loading the subject list only blocks a submit that needs a subject.
        if (!syncSession() || mutable.value.busy || (mutable.value.loading && mutable.value.subjectId != null)) return
        val state = mutable.value
        val subject = state.subjectId
        val payload = listOf(subject.orEmpty(), state.name.trim(), state.description.trim())
        if (saved.get<ArrayList<String>>("payload") != ArrayList(payload)) {
            saved["request"] = UUID.randomUUID().toString(); saved["payload"] = ArrayList(payload)
        }
        val key = saved.get<String>("request") ?: UUID.randomUUID().toString().also { saved["request"] = it }
        val request = ++generation
        mutable.value = state.copy(busy = true, error = null)
        job = scope.launch {
            val result = groups.create(CreateGroupRequest(key, subject, state.name, state.description))
            if (!syncSession() || request != generation) return@launch
            mutable.value = when (result) {
                is ApiResponse.Success -> mutable.value.copy(busy = false, created = result.data.id)
                is ApiResponse.Failure -> mutable.value.copy(busy = false, error = result.error.message)
            }
        }
    }
    fun createdOpened() { clearDraft() }
}

data class GroupDetailState(val detail: GroupDetail? = null, val loading: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val closed: Boolean = false, val saved: Boolean = false,
    val draftName: String = "", val draftDescription: String = "", val dirty: Boolean = false)
class GroupDetailViewModel @JvmOverloads constructor(
    private val groups: GroupRepository = GroupRepository(),
    private val externalScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(GroupDetailState())
    val state = mutable.asStateFlow()
    private var id: String? = null
    private var identity = groups.currentUserId() to groups.currentSessionKey()
    private var generation = 0
    private var job: Job? = null
    init { scope.launch { groups.sessionChanges().collect { syncSession() } } }
    private fun syncSession(): Boolean {
        val current = groups.currentUserId() to groups.currentSessionKey()
        if (identity != current) { identity = current; generation++; job?.cancel(); mutable.value = GroupDetailState(closed = true) }
        return current.first != null
    }
    fun show(groupId: String) { id = groupId; refresh() }
    fun refresh() {
        if (!syncSession() || mutable.value.loading || mutable.value.busy) return
        val groupId = id ?: return
        val request = ++generation
        mutable.value = mutable.value.copy(loading = true, error = null)
        job = scope.launch {
            val result = groups.detail(groupId)
            if (!syncSession() || request != generation) return@launch
            render(result)
        }
    }
    fun edit(name: String, description: String) {
        if (!syncSession() || mutable.value.busy || mutable.value.detail?.role != GroupRole.OWNER) return
        mutable.value = mutable.value.copy(draftName = name, draftDescription = description, dirty = true)
    }
    fun save(name: String, description: String) {
        edit(name, description)
        mutate { groups.update(it, name, description) }
    }
    fun leave() = mutate(close = true) { groups.leave(it) }
    fun archive() = mutate(close = true) { groups.archive(it) }
    fun acknowledged() { mutable.value = mutable.value.copy(saved = false) }
    private fun mutate(close: Boolean = false, block: suspend (String) -> ApiResponse<*>) {
        if (!syncSession() || mutable.value.busy || mutable.value.loading) return
        val groupId = id ?: return
        val request = ++generation
        mutable.value = mutable.value.copy(busy = true, error = null)
        job = scope.launch {
            val result = block(groupId)
            if (!syncSession() || request != generation) return@launch
            if (result is ApiResponse.Failure && result.error.type.revokesGroupAccess()) {
                mutable.value = GroupDetailState(error = result.error.message, closed = true)
                return@launch
            }
            if (close && result is ApiResponse.Success) { mutable.value = GroupDetailState(closed = true); return@launch }
            if (result is ApiResponse.Success) mutable.value = mutable.value.copy(dirty = false)
            val refreshed = groups.detail(groupId)
            if (!syncSession() || request != generation) return@launch
            render(refreshed)
            if (result is ApiResponse.Failure && !mutable.value.closed) mutable.value = mutable.value.copy(error = result.error.message)
            else if (result is ApiResponse.Success && !mutable.value.closed) mutable.value = mutable.value.copy(saved = true)
        }
    }
    private fun render(result: ApiResponse<GroupDetail>) {
        mutable.value = when (result) {
            is ApiResponse.Success -> {
                val retainDraft = mutable.value.dirty && result.data.role == GroupRole.OWNER
                GroupDetailState(detail = result.data,
                    draftName = if (retainDraft) mutable.value.draftName else result.data.name,
                    draftDescription = if (retainDraft) mutable.value.draftDescription else result.data.description.orEmpty(),
                    dirty = retainDraft)
            }
            is ApiResponse.Failure -> {
                val lost = result.error.type.revokesGroupAccess()
                if (lost) GroupDetailState(error = result.error.message, closed = true)
                else mutable.value.copy(loading = false, busy = false, error = result.error.message)
            }
        }
    }
}
