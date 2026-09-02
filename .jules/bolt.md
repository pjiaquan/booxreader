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

## 2026-08-19 - Prevent Blind REPLACE in Sync processing
**Learning:** Blindly inserting entities into a Room DAO using `OnConflictStrategy.REPLACE` inside a loop causes excessive disk I/O and SQLite churn even if the data hasn't changed.
**Action:** Pre-fetch existing entities, compare timestamps or content (e.g., `bookmark.updatedAt > existing.updatedAt`), and conditionally skip the `insert()` operation when the data is identical or stale.


## 2024-05-14 - Optimize redundant DB queries with in-memory tracking
**Learning:** When replacing redundant database queries (`getAllList()`) with an existing in-memory list, ensure any intervening database state changes (like deletions) are reflected in the cached list.
**Action:** Use a tracking collection (e.g., `deletedIds = mutableSetOf<Long>()`) to record deletions and filter the reused memory list against it (e.g., `allProfiles.filter { it.id !in deletedIds }`) before processing.
## 2026-08-25 - Optimize Room Sync with Batch Inserts
**Learning:** Blindly inserting entities in a loop using Room's OnConflictStrategy.REPLACE creates O(N) transaction overhead which severely degrades performance during sync.
**Action:** Pre-fetch existing entities, compare them, and accumulate changes in a list to use `insertBatch` with `chunked(900)` inside a single transaction to significantly reduce SQLite churn and disk I/O.

## 2026-08-31 - Avoid chunking for batch inserts/updates in Room
**Learning:** Room automatically iterates list parameters for @Insert and @Update batch operations, bypassing SQLite's parameter limits. Chunking is unnecessary and degrades performance compared to a single transaction.
**Action:** Never use `.chunked()` for `@Insert` or `@Update` list operations in Room DAOs; only use it for `IN` clause queries.

## 2026-09-02 - Always use existing private helper functions for caching
**Learning:** When matching or caching items, always check for and use existing private helper functions (like `profileNameKey`) to ensure correct manipulation logic (such as string trimming and capitalization).
**Action:** Use tools like `grep` or check for helper functions near the loop logic before writing custom string manipulation code to avoid logic issues.
