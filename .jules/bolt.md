## 2025-02-12 - Memory limits on database operations
**Learning:** Room/SQLite has a limit on the number of variables in an IN clause (usually 999). Operating on large lists without chunking will crash with 'Too many SQL variables'.
**Action:** Always use `.chunked(900)` or similar when passing large collections to Room IN queries (e.g. `deleteByIds`, `getByIds`).
## 2025-03-05 - Avoid IN clauses for single-entity lookups
**Learning:** Room/SQLite incurs unnecessary overhead when using an `IN` clause (e.g. `getByIds(listOf(id))`) for single entity lookups, including list allocation, SQLite IN operator processing, and collection extraction operations like `.firstOrNull()`.
**Action:** Always create and use a dedicated `getById(id)` method with an exact match query (`WHERE id = :id`) instead of reusing batch methods with single-element lists.
## 2025-03-08 - N+1 Queries during Synchronization
**Learning:** Calling `getById(id)` repeatedly in a loop for cloud synchronization causes unnecessary overhead and delays due to multiple individual Room/SQLite queries.
**Action:** When processing remote data lists (e.g., syncing items), always pre-fetch the corresponding local records in batches using `chunked(900)` and cache them in an in-memory Map (`associateBy`) for O(1) lookups during the iteration.
