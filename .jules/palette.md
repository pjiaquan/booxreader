## 2024-05-18 - Added accessibility to selectable items
**Learning:** Found checkboxes in Android item layouts (e.g., `item_ai_note_selectable.xml`) that were completely missing textual descriptors (`contentDescription` or `text`), making them invisible to screen readers or unintelligible.
**Action:** Always ensure that `contentDescription` is dynamically assigned in adapter code when adjacent item text changes, ensuring screen readers announce the exact context of the selection (e.g. `getString(R.string.ai_note_select_note, noteText)`).
