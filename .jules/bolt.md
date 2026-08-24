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
## 2024-05-24 - Batch Room Inserts During Sync loops
**Learning:** During sync operations (like `pullBooks`), running single `db.dao().insert()` operations in a loop creates an O(N) database I/O bottleneck as each insert spins up its own implicit SQLite transaction overhead.
**Action:** Always prefer accumulating entities in a list, wrapping them in a chunked operation (e.g. `chunked(900)` to respect parameter limits), and using a batched `@Insert(onConflict = OnConflictStrategy.REPLACE)` DAO method (e.g. `insertBatch`) wrapped in a single explicit transaction.
