## 2024-05-24 - Missing Empty States in List Views
**Learning:** Found that multiple list activities in the app (like `BookmarkListActivity`, `AiNoteListActivity`, etc.) lack empty states. When a list is empty, users see a blank screen instead of a helpful prompt, which can lead to confusion.
**Action:** Always check for an empty state implementation when creating or modifying activities/fragments that display data lists. A `TextView` configured for empty state should be present and toggled based on the adapter's data count.
