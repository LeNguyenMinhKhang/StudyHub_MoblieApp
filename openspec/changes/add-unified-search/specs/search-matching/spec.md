## ADDED Requirements

### Requirement: Accent-insensitive query normalization
The system SHALL compare search queries and searchable fields after the same server-side normalization: trim, lowercase, remove Vietnamese diacritics including mapping đ/Đ to d, treat `_`, `.`, `-` and `/` as word separators, and collapse consecutive whitespace. Normalization MUST be applied identically to stored values and to the query so that accented, unaccented and mixed-case input match the same records. Characters `%`, `_` and `\` typed by the user MUST be matched literally and MUST NOT act as pattern wildcards.

#### Scenario: Unaccented query matches accented data
- **WHEN** an eligible viewer searches subjects for "toan cao cap"
- **THEN** the active subject named "Toán cao cấp" is returned

#### Scenario: Case and đ normalization
- **WHEN** an eligible viewer searches for "DE THI DAI SO"
- **THEN** records whose matched field contains "Đề thi đại số" are returned

#### Scenario: Separators in file names
- **WHEN** an eligible viewer searches documents for "giai tich 2023"
- **THEN** a visible non-image file named "De_thi_giai-tich.2023.pdf" is returned

#### Scenario: Wildcard characters are literal
- **WHEN** an eligible viewer searches for "100%"
- **THEN** only records whose normalized field contains "100%" are returned, not every record containing "100"

### Requirement: Query length limits
The system SHALL search only when the normalized query contains at least 2 characters. A normalized query shorter than 2 characters MUST produce an empty result page without an error. A raw query longer than 100 characters MUST be rejected as invalid input, and the Android input SHALL prevent entering more than 100 characters.

#### Scenario: Too-short query
- **WHEN** a viewer enters "a" or a query that normalizes to fewer than 2 characters such as "__"
- **THEN** no results are returned and no validation error is shown

#### Scenario: Oversized query
- **WHEN** a client calls a search RPC with a 101-character query
- **THEN** the server rejects the request with the invalid-input error code and returns no results

### Requirement: Tiered matching with typo tolerance
The system SHALL classify each candidate by the best tier it satisfies on that tab's match fields: tier 1 when a normalized field equals or starts with the normalized query, tier 2 when it contains the query as a substring, and tier 3 when trigram word similarity between the query and the field meets the server-configured threshold. Tier 3 MUST be evaluated only when the normalized query has at least 4 characters and contains only letters, digits and spaces; a query containing other symbols such as `%`, `+` or `#` SHALL be matched literally through tiers 1–2 because trigrams ignore symbols. Fields designated substring-only by a tab MUST participate in tiers 1–2 only. The threshold MUST be configured on the server, not supplied by the client.

#### Scenario: Misspelled query
- **WHEN** an eligible viewer searches subjects for "giai tihc" and an active subject named "Giải tích 1" exists
- **THEN** "Giải tích 1" is returned as a tier 3 result

#### Scenario: Short query does not use fuzzy matching
- **WHEN** an eligible viewer searches for the 3-character query "abc"
- **THEN** only tier 1 and tier 2 matches are returned

#### Scenario: Symbol query stays literal
- **WHEN** an eligible viewer searches for "c++" and active subjects "C++ cơ bản" and "C cơ bản" exist
- **THEN** only "C++ cơ bản" is returned

#### Scenario: Unrelated text is not matched
- **WHEN** an eligible viewer searches for a 4-or-more-character query whose trigram word similarity with every match field is below the threshold and which no field contains
- **THEN** that record is not returned

### Requirement: Deterministic relevance ordering
The system SHALL order results by tier ascending, then records whose original text contains the query with identical diacritics before records matching only after accent removal, then trigram similarity descending, then the tab's secondary key, then id ascending. Secondary keys SHALL be newest first for posts and documents, name ascending for subjects, and username ascending for users. Identical input against unchanged data MUST return identical order.

#### Scenario: Tier ordering
- **WHEN** a viewer searches subjects for "toan" and active subjects "Toán rời rạc" (prefix), "Nhập môn toán" (substring) and "Toam hoc" (fuzzy only) all match
- **THEN** results appear in the order "Toán rời rạc", "Nhập môn toán", "Toam hoc"

#### Scenario: Exact diacritics rank first
- **WHEN** a viewer searches "mã nguồn" and both "Mã nguồn mở" and "Ma nguon mo" are tier 1 matches
- **THEN** "Mã nguồn mở" is ranked before "Ma nguon mo"

#### Scenario: Stable repeated search
- **WHEN** the same query, tab and page are requested twice without data changes
- **THEN** both responses contain the same items in the same order

### Requirement: Bounded offset pagination
Each search RPC SHALL accept a page size and a non-negative offset, default to 20 items, cap the page size at 50, and cap the reachable window at the first 100 ranked results. The response MUST contain the ordered items and a has-more flag that is true only when another non-empty page exists within the window. A nonpositive page size, negative offset or null pagination input MUST be rejected as invalid input.

#### Scenario: Load the next page
- **WHEN** 45 results match and the client requests offset 0 then offset 20 then offset 40 with page size 20
- **THEN** the pages contain 20, 20 and 5 items in ranked order, and only the last page has has-more false

#### Scenario: Window cap
- **WHEN** more than 100 results match and the client requests offset 80 with page size 50
- **THEN** the response contains at most 20 items and has-more is false

#### Scenario: Invalid pagination
- **WHEN** a client requests page size 0 or offset -1
- **THEN** the server rejects the request with the invalid-input error code
