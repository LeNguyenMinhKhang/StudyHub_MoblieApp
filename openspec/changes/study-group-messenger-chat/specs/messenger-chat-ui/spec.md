## ADDED Requirements

### Requirement: Messenger-style conversation header
The chat screen SHALL show a fixed header containing a back icon, a circular avatar, a title and a one-line subtitle, plus trailing action icons. In a direct conversation the avatar and title are the other user's; in a group conversation they are a group avatar (initials of the group name on a colored circle) and the group name, the subtitle is the member count, and the trailing icons are audio call, video call and group info.

#### Scenario: Group header
- **WHEN** a member opens the chat of group "Giải tích 1" with 5 members
- **THEN** the header shows the initials avatar, "Giải tích 1", "5 thành viên", and audio-call, video-call and info icons

#### Scenario: Call icons start or join a call
- **WHEN** the member taps the audio-call or video-call icon
- **THEN** the existing group call screen opens in that mode, joining the open call if one exists, with the same capacity and "call in another group" rules as before

#### Scenario: Call icons disabled
- **WHEN** the user is already in a call of another group, or the open call is full
- **THEN** the call icons are disabled and their content description explains why

#### Scenario: Info icon
- **WHEN** the member taps the info icon
- **THEN** the Group info screen opens

### Requirement: Ongoing call bar
The group chat screen SHALL show a compact bar below the header whenever the group has an open call or the user is in this group's call, polling the call state at the existing interval only while the screen is visible.

#### Scenario: Others are in a call
- **WHEN** the group has an active call with 3 of 6 participants and the user is not in it
- **THEN** a bar shows the participant count and a "Tham gia" action that opens the call screen

#### Scenario: User is in this group's call
- **WHEN** the user has an active call session in this group and returns to the chat
- **THEN** the bar shows "Quay lại cuộc gọi" and tapping it returns to the call screen

#### Scenario: No call
- **WHEN** the group has no open call
- **THEN** the bar is hidden

### Requirement: Grouped message bubbles
The message list SHALL render own messages right-aligned in the primary color and received messages left-aligned in a neutral color, with rounded bubbles. Consecutive messages from the same sender within 5 minutes SHALL form a run: inner corners are tightened, only the last bubble of a received run shows the sender avatar, and in group conversations only the first bubble of a received run shows the sender name. A centered time separator SHALL appear before a message sent more than 15 minutes after the previous one or on a different day.

#### Scenario: Run of three messages
- **WHEN** another member sends three messages within two minutes
- **THEN** they render as one run with the sender name above the first bubble, the avatar next to the third bubble only, and tightened corners between them

#### Scenario: Gap in time
- **WHEN** a message is sent 20 minutes after the previous one
- **THEN** a centered time separator appears above it and it starts a new run

#### Scenario: Own message status
- **WHEN** the user's latest own message is sending, sent, read (direct only) or failed
- **THEN** a small status icon shows beside that message, and a failed icon is tappable to retry

#### Scenario: Image and document messages
- **WHEN** a message is an image or a document
- **THEN** the image renders as a rounded thumbnail and the document as a card with a file icon, name and size, and tapping either opens it

### Requirement: Messenger-style input bar
The input bar SHALL show image and attachment icons on the left, a pill-shaped multi-line text field in the middle and a send icon on the right. While the text field contains text, the left icons SHALL collapse into a single expand icon to give the field more room. While a message is sending, the send icon SHALL remain usable for further messages.

#### Scenario: Typing collapses actions
- **WHEN** the user types in the text field
- **THEN** the image and attachment icons are replaced by an expand icon, and tapping it shows them again

#### Scenario: Empty field
- **WHEN** the text field is empty or only whitespace
- **THEN** the send icon is disabled

#### Scenario: Keyboard opens
- **WHEN** the keyboard opens
- **THEN** the input bar stays above the keyboard and the list keeps the newest message visible

### Requirement: Opening a group goes to its chat
Opening a group from the "Nhóm của tôi" list, after creating a group, or after accepting an invitation and opening the group SHALL show the group chat screen. Group details SHALL be reached from the info icon.

#### Scenario: Tap a group in the list
- **WHEN** the user taps a group row in "Nhóm của tôi"
- **THEN** the group chat screen opens

#### Scenario: Group created
- **WHEN** the user finishes creating a group
- **THEN** the new group's chat screen opens with an empty-state message inviting members to start the conversation

#### Scenario: Group becomes unavailable while open
- **WHEN** the user is removed from the group or the group is archived while its chat or info screen is open
- **THEN** a message explains the group is no longer available and the group screens close, as the existing group detail flow does

### Requirement: Group info screen
The Group info screen SHALL show the group avatar, name, subject (only when the group has one) and member count at the top, then icon rows for: edit name and description (owner only), Add member (every member), Members, Documents, Leave group (non-owner) and Archive group (owner, destructive color). Existing confirmation dialogs, permissions and error handling for these actions SHALL be preserved.

#### Scenario: Owner view
- **WHEN** the owner opens Group info
- **THEN** the edit, Add member, Members, Documents and Archive rows are shown and Leave is hidden

#### Scenario: Member view
- **WHEN** a non-owner member opens Group info
- **THEN** name and description are read-only, and Add member, Members, Documents and Leave rows are shown

#### Scenario: Leave from info
- **WHEN** a member taps Leave group and confirms
- **THEN** the member leaves and all group screens close

### Requirement: Icon-based function buttons in group screens
Function buttons in group screens SHALL be icon buttons instead of text buttons: back navigation in group, members, documents and call screens; upload in the document library; and the call controls (microphone, camera, switch camera, audio route, leave, end for everyone). Every icon button MUST have a localized content description, a touch target of at least 48dp, and a visibly distinct state for toggles such as microphone and camera off. Primary list actions with text meaning (accept/decline invitation, invite, remove, transfer) MAY remain text buttons.

#### Scenario: Upload in document library
- **WHEN** a member opens the group document library
- **THEN** an upload icon is shown in the top bar and tapping it opens the PDF picker as the old Upload button did

#### Scenario: Toggle microphone in a call
- **WHEN** the user taps the microphone icon during a call
- **THEN** the microphone mutes, the icon switches to the muted variant, and its content description changes to "Bật micro"

#### Scenario: End call for everyone
- **WHEN** the end-for-everyone control is shown because the current user is allowed to end the call
- **THEN** it is a red icon distinct from the leave icon, and tapping it still asks for confirmation before ending the call

#### Scenario: Screen reader
- **WHEN** TalkBack focuses any of these icon buttons
- **THEN** it announces the action name rather than "unlabelled button"
