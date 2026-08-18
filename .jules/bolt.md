## 2025-02-12 - Memory limits on database operations
**Learning:** Room/SQLite has a limit on the number of variables in an IN clause (usually 999). Operating on large lists without chunking will crash with 'Too many SQL variables'.
**Action:** Always use `.chunked(900)` or similar when passing large collections to Room IN queries (e.g. `deleteByIds`, `getByIds`).
## 2025-03-05 - Avoid IN clauses for single-entity lookups
**Learning:** Room/SQLite incurs unnecessary overhead when using an `IN` clause (e.g. `getByIds(listOf(id))`) for single entity lookups, including list allocation, SQLite IN operator processing, and collection extraction operations like `.firstOrNull()`.
**Action:** Always create and use a dedicated `getById(id)` method with an exact match query (`WHERE id = :id`) instead of reusing batch methods with single-element lists.

## 2024-05-18 - [Optimized N+1 Room query in Sync processing]
**Learning:** Room database queries executed inside loops over large lists (e.g., syncing items from remote to local) will cause significant N+1 query bottlenecks and GC churn, drastically slowing down synchronization on large collections.
**Action:** Always pre-fetch needed entities via chunked IN queries (`getByIds(ids)`) before the loop, and construct an in-memory map (`associateBy`) for O(1) lookups during iteration. Update the map when entities are saved locally to maintain correct state inside the loop.
## 2025-03-08 - Precision in search-and-replace
**Learning:** Using overly generic SEARCH blocks in `replace_with_git_merge_diff` can cause identical blocks of code in completely unrelated functions to be mistakenly patched, introducing dead code or logic errors.
**Action:** Always include surrounding context (like function definitions, specific variable declarations, or unique comments) in the SEARCH block to ensure the patch applies exactly where intended.
## 2025-03-08 - Optimized N+1 Room query in Bookmark Sync processing
**Learning:** Room database queries executed inside loops over large lists (e.g., syncing bookmarks from remote to local) will cause significant N+1 query bottlenecks and GC churn, drastically slowing down synchronization on large collections.
**Action:** Always pre-fetch needed entities via chunked IN queries (`getByRemoteIds(ids)`) before the loop, and construct an in-memory map (`associateBy`) for O(1) lookups during iteration. Update the map when entities are saved locally to maintain correct state inside the loop. Avoid unconditionally inserting items inside a loop without checking if an existing item in the DB matches.
