## 2024-05-24 - Missing Empty States in List Views
**Learning:** Found that multiple list activities in the app (like `BookmarkListActivity`, `AiNoteListActivity`, etc.) lack empty states. When a list is empty, users see a blank screen instead of a helpful prompt, which can lead to confusion.
**Action:** Always check for an empty state implementation when creating or modifying activities/fragments that display data lists. A `TextView` configured for empty state should be present and toggled based on the adapter's data count.

## 2026-07-30 - Missing Content Description in ImageView Elements
**Learning:** In standard Android UI files, `ImageView` elements often lack `contentDescription` attributes, which are vital for screen reader accessibility, particularly when used to indicate state (e.g., checkmarks for selection).
**Action:** When adding or modifying `ImageView` tags used for state or as interactive buttons, ensure they have a descriptive `android:contentDescription` to provide proper feedback for accessibility tools.

## 2024-07-31 - Hardcoded contentDescription in FloatingActionButton
**Learning:** Found that `activity_magic_tag_list.xml` had a hardcoded `contentDescription="Add Tag"`. Hardcoded strings for accessibility descriptions should be avoided as they cannot be localized.
**Action:** Always extract hardcoded accessibility descriptions to `strings.xml` using the `@string/` format, just like regular text strings, to ensure proper localization support for screen reader users.
