## ADDED Requirements

### Requirement: One conversation per active study group
The system SHALL associate each active study group with exactly one group conversation, stored in the existing `conversations` table with `type = 'group'` and a unique reference to the study group. The server SHALL create the conversation on first request in an idempotent way, and concurrent first requests MUST resolve to the same conversation.

#### Scenario: First member opens the group chat
- **WHEN** a current member of an active study group requests that group's conversation and none exists yet
- **THEN** the server creates one group conversation linked to the group and returns its id, the group name and the member count

#### Scenario: Concurrent first requests
- **WHEN** two members request the conversation of the same group at the same time and none exists yet
- **THEN** both receive the same conversation id and only one conversation row exists for that group

#### Scenario: Non-member requests the conversation
- **WHEN** a signed-in user who is not a member of the group, or any user for an archived group, requests the group's conversation
- **THEN** the server rejects the request with the existing "group unavailable" error and creates nothing

### Requirement: Study group membership authorizes group chat
The system SHALL authorize reading messages, receiving realtime changes, sending messages and marking a group conversation as read only for users who are current members of the linked study group, while the group is not archived and the user's account is not banned. Client-side checks MUST NOT be treated as authorization.

#### Scenario: Member reads history
- **WHEN** a current member loads messages of the group conversation
- **THEN** the member receives all messages of that conversation in chronological order, including messages sent before they joined

#### Scenario: Removed member loses access
- **WHEN** a member leaves the group or is removed by the owner
- **THEN** subsequent message reads by that user return no rows, sends are rejected with a permission error, and realtime no longer delivers that conversation's new messages to them

#### Scenario: Archived group closes the chat
- **WHEN** the owner archives the group
- **THEN** every send to the group conversation is rejected, the conversation no longer appears in any user's conversation list, and messages can no longer be read

#### Scenario: Member joins by accepting an invitation
- **WHEN** an invited user accepts a study group invitation
- **THEN** the user can immediately open the group conversation, read its history and send messages

#### Scenario: Direct insert bypassing the RPC
- **WHEN** a non-member attempts to insert into `messages` for a group conversation directly through PostgREST
- **THEN** row level security rejects the insert

### Requirement: Send text, image and document messages in a group
The system SHALL let group members send text, image and document messages in the group conversation through the same send flow as direct chat: the message appears immediately with a sending state, becomes sent on success, becomes failed with a retry affordance on error, and new messages from other members arrive in realtime without reloading.

#### Scenario: Send a text message
- **WHEN** a member types a non-blank message and taps the send icon
- **THEN** the message appears at the bottom with a sending state, then sent, and the other members viewing the conversation see it in realtime

#### Scenario: Send an image or document
- **WHEN** a member taps the image icon or the attachment icon and picks a file within the existing 20 MB document limit
- **THEN** the file is uploaded under the group conversation's storage path and a message of type `image` or `document` is sent and rendered as an image or a document card

#### Scenario: Blank or oversized content
- **WHEN** a member tries to send only whitespace or a document larger than 20 MB
- **THEN** nothing is sent and, for the oversized file, a message explains the limit

#### Scenario: Send fails and is retried
- **WHEN** a send fails because of a network error
- **THEN** the message shows a failed icon, and tapping it resends the same content without creating a duplicate once the original is confirmed

### Requirement: Show the sender of received group messages
The system SHALL show the display name and avatar of the sender for messages received in a group conversation, including senders who are no longer members. Sender profiles MUST be resolved in batches rather than one request per message.

#### Scenario: Messages from several members
- **WHEN** a member opens a group conversation containing messages from three other members
- **THEN** each run of consecutive messages from the same sender shows that sender's name above the first bubble and avatar beside the last bubble

#### Scenario: Sender left the group
- **WHEN** a message was sent by a user who has since left the group
- **THEN** the message still shows that user's display name and avatar

#### Scenario: Profile lookup fails
- **WHEN** sender profiles cannot be loaded
- **THEN** the messages still render with a default avatar and a generic sender label instead of failing the whole conversation

### Requirement: Read state in group conversations
The system SHALL record each member's last read time for the group conversation and use it for unread counts in the conversation list. The group conversation SHALL NOT display per-message "read" receipts; own messages show only sending, sent or failed states.

#### Scenario: Opening the conversation clears unread count
- **WHEN** a member with unread group messages opens the group conversation
- **THEN** their last read time is updated and the group's unread count in the Chat tab becomes zero

#### Scenario: Own message read by someone
- **WHEN** another member reads a message the current user sent in the group
- **THEN** the current user's message still shows the sent state, not a read receipt

### Requirement: Group conversations appear in the Chat tab
The system SHALL include the group conversations of the user's active study groups in the Chat tab list, showing the group name, a group icon, the last message preview and the unread count, ordered with direct conversations by last message time. Group conversations without any message MAY be omitted.

#### Scenario: Group with recent message
- **WHEN** a member's group has a newer message than any direct conversation
- **THEN** the group appears first in the Chat tab with the group name, group icon and a preview such as "[Hình ảnh]" for an image

#### Scenario: Open a group from the Chat tab
- **WHEN** the user taps a group conversation in the Chat tab
- **THEN** the group chat screen opens for that group, not the direct chat screen

#### Scenario: User left the group
- **WHEN** the user is no longer a member of the group or the group is archived
- **THEN** that group conversation is not listed

### Requirement: Direct chat behavior is preserved
The system SHALL keep existing direct chat behavior unchanged while sharing code with group chat, including get-or-create of a direct conversation, read receipts and the rule that blocked users cannot see or send in a direct conversation.

#### Scenario: Blocked direct conversation
- **WHEN** either user in a direct conversation has blocked the other
- **THEN** sending a message is rejected by the server and the conversation is hidden from the list, as before this change

#### Scenario: Read receipt in direct chat
- **WHEN** the recipient opens a direct conversation
- **THEN** the sender's earlier messages show the read icon, as before this change
