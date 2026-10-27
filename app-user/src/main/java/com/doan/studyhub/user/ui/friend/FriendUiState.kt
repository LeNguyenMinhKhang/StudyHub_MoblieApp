package com.doan.studyhub.user.ui.friend

import com.doan.studyhub.core.data.model.*

data class FriendTabState(
    val items: List<FriendshipListItem> = emptyList(),
    val cursor: FriendshipCursor? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null
)

data class FriendUiState(
    val selected: FriendshipListKind = FriendshipListKind.FRIENDS,
    val tabs: Map<FriendshipListKind, FriendTabState> = FriendshipListKind.entries.associateWith { FriendTabState() },
    val busy: Set<String> = emptySet(),
    val unresolved: Set<String> = emptySet(),
    val rowErrors: Map<String, String> = emptyMap()
) {
    val tab: FriendTabState get() = tabs.getValue(selected)
}

data class FriendshipUiState(
    val snapshot: FriendshipSnapshot? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val reconciled: Boolean = false,
    val error: String? = null
) {
    val actions: List<FriendshipAction> get() = if (loading || busy || !reconciled) emptyList() else when (snapshot?.state) {
        FriendshipState.NONE -> listOf(FriendshipAction.SEND)
        FriendshipState.OUTGOING_PENDING -> listOf(FriendshipAction.CANCEL)
        FriendshipState.INCOMING_PENDING -> listOf(FriendshipAction.ACCEPT, FriendshipAction.DECLINE)
        FriendshipState.FRIENDS -> listOf(FriendshipAction.REMOVE)
        else -> emptyList()
    }
}
