## ADDED Requirements

### Requirement: Unified search screen
The Android app SHALL provide a Tìm kiếm screen opened from a search action in Home's top bar through Home's detail navigation. The screen SHALL contain one query input and the tabs Người dùng, Bài viết, Môn học and Tài liệu in that order. A query change SHALL trigger a search for the active tab after the user stops typing for 300 ms, reset every tab's results and pagination, and load other tabs only when they are selected. Responses for a superseded query or tab MUST be discarded. Navigating back from the screen SHALL return to the screen that opened it.

#### Scenario: Open and search
- **WHEN** the user taps the top-bar search action, types "giai tich" and pauses
- **THEN** the Tìm kiếm screen shows Người dùng results for "giai tich" and has not loaded the other three tabs

#### Scenario: Switch tab with the same query
- **WHEN** the user selects Bài viết after Người dùng results are shown
- **THEN** Bài viết results for the same query load, and returning to Người dùng shows its earlier results without reloading

#### Scenario: Typing quickly
- **WHEN** the user types "g", "gi", "gia" within 300 ms and an earlier request returns after a later one
- **THEN** at most the final query is requested after the pause and only results for "gia" are displayed

#### Scenario: Back navigation
- **WHEN** the user opens search from the feed and presses back
- **THEN** the feed is shown again

### Requirement: Server-enforced search access
Every search RPC SHALL derive the viewer from the authenticated session and MUST be executable only by the authenticated role. An anonymous caller MUST be denied, and a banned viewer MUST receive the banned-account error. All filtering by visibility, ban, block and activity status MUST happen on the server before results are returned; the client MUST NOT receive records it is not allowed to see and then hide them locally. Responses MUST contain only the fields defined for each tab.

#### Scenario: Anonymous caller
- **WHEN** a caller without a session invokes any search RPC
- **THEN** the call is denied and no data is returned

#### Scenario: Banned viewer
- **WHEN** a banned account invokes a search RPC
- **THEN** the server returns the banned-account error and no results

#### Scenario: Hidden records never reach the client
- **WHEN** a viewer's query matches a private post of another user
- **THEN** the raw RPC response does not contain that post or any of its files

### Requirement: User search results
The Người dùng tab SHALL match `username` and `display_name` using all three matching tiers. It MUST exclude banned profiles and profiles where a block exists in either direction between the viewer and that profile, and it SHALL include the viewer's own profile when it matches. Each item MUST contain only id, username, display name and avatar URL. Selecting an item SHALL open that public profile through the existing profile screen, and the existing exact-username lookup dialog SHALL remain available.

#### Scenario: Partial and misspelled name
- **WHEN** eligible user "nguyenvana" with display name "Nguyễn Văn An" exists and the viewer searches "nguyen van" or "nguyen vna an"
- **THEN** that user appears in the Người dùng results

#### Scenario: Blocked either direction
- **WHEN** A searches for B's username and either A blocked B or B blocked A
- **THEN** B is not returned to A

#### Scenario: Banned profile
- **WHEN** a banned user's username matches the query
- **THEN** that user is not returned

#### Scenario: Restricted projection
- **WHEN** any user is returned
- **THEN** the item contains no role, ban flag, email or other non-public field

#### Scenario: Open a user
- **WHEN** the viewer selects a user result and then presses back
- **THEN** the public profile opens and back returns to the search screen with the same query and results

### Requirement: Post search results
The Bài viết tab SHALL match post `title` using all three tiers and post `content` as substring-only. It MUST return a post only when the viewer is the author, the post visibility is public, or the visibility is friends and the viewer and author have an accepted friendship. Visibility MUST be derived from the post's stored type using the same rules as the app's current parser, including legacy single-part values, and friendship MUST be evaluated on the server without a limit on the number of friends. Posts whose author is banned or has a block in either direction with the viewer MUST be excluded. Results SHALL be shown with the existing post card including author, subject, attachments, like count and comment count, and selecting a result SHALL open the existing post detail.

#### Scenario: Visibility matrix
- **WHEN** author A has matching public, friends and private posts and viewers A, friend F and stranger S search the same query
- **THEN** A receives all three, F receives the public and friends posts, and S receives only the public post

#### Scenario: Friend beyond the first hundred
- **WHEN** a viewer has more than 100 friends and the 150th friend's friends-only post matches
- **THEN** that post is returned to the viewer

#### Scenario: Legacy type values
- **WHEN** matching posts have type null, "question" or "friends" without a separator
- **THEN** they are treated respectively as public, public and friends-only, as the app parser does

#### Scenario: Content match
- **WHEN** a public post's title does not match but its content contains the normalized query
- **THEN** the post is returned as a tier 2 result

#### Scenario: Blocked author
- **WHEN** the viewer and a public post's author have a block in either direction
- **THEN** that post is not returned

### Requirement: Document search results
The Tài liệu tab SHALL return individual attached files that are not images, where images are files whose type starts with `image/` or whose name ends with a common image extension. It SHALL match the file name without its extension using all three tiers and the parent post title as substring-only, and MUST apply the same visibility and author-availability rules as post search to the parent post. Each item MUST contain the file id, file name, file type, file size, parent post id, parent post title, author display name and file created time. Selecting an item SHALL open the parent post detail.

#### Scenario: Non-image files only
- **WHEN** a visible post has "anh-bia.png" and "giai-tich-chuong-1.pdf" and the viewer searches "giai tich"
- **THEN** only the PDF file is returned

#### Scenario: Match by post title
- **WHEN** a visible post titled "Tài liệu ôn thi Giải tích" has an attachment named "file1.docx" and the viewer searches "giai tich"
- **THEN** "file1.docx" is returned

#### Scenario: Private parent post
- **WHEN** a matching PDF belongs to another user's private post
- **THEN** the file is not returned

#### Scenario: Open a document
- **WHEN** the viewer selects a document result
- **THEN** the parent post detail opens with its attachments

### Requirement: Subject search results
The Môn học tab SHALL return only active subjects, matching `name` using all three tiers and `description` as substring-only. Each item SHALL show the subject name, description and the viewer's current follow state using the existing subject row behavior, and selecting an item SHALL open the existing subject detail.

#### Scenario: Inactive subject
- **WHEN** an inactive subject's name matches the query
- **THEN** it is not returned

#### Scenario: Follow state
- **WHEN** the viewer follows a returned subject
- **THEN** the result shows the followed state and follow/unfollow uses the existing subject follow behavior

### Requirement: Search result states and pagination UI
Each tab SHALL show distinct idle, loading, results, empty, error and loading-more states. The idle state SHALL prompt for at least 2 characters. An error on the first page SHALL show a safe message with retry, while an error when loading more SHALL keep existing results and allow retrying that page. Loading more SHALL request the next offset only when has-more is true, append items without duplicating ids, and never run two page requests for the same tab at once.

#### Scenario: Idle and empty
- **WHEN** the query has fewer than 2 characters, or a valid query has no matches
- **THEN** the tab shows respectively the minimum-length prompt or an empty message for that query

#### Scenario: First-page error and retry
- **WHEN** the first page fails because the network is unavailable and the user taps retry after reconnecting
- **THEN** a safe Vietnamese error with retry is shown and retry loads the first page

#### Scenario: Load-more failure keeps results
- **WHEN** the second page fails after the first page loaded
- **THEN** first-page results remain visible and retry requests the second page again

#### Scenario: No duplicate items
- **WHEN** data changes between page requests so that an item appears on two pages
- **THEN** the item is shown once
