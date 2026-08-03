## 2025-02-12 - Memory limits on database operations
**Learning:** Room/SQLite has a limit on the number of variables in an IN clause (usually 999). Operating on large lists without chunking will crash with 'Too many SQL variables'.
**Action:** Always use `.chunked(900)` or similar when passing large collections to Room IN queries (e.g. `deleteByIds`, `getByIds`).
## 2025-03-05 - Avoid IN clauses for single-entity lookups
**Learning:** Room/SQLite incurs unnecessary overhead when using an `IN` clause (e.g. `getByIds(listOf(id))`) for single entity lookups, including list allocation, SQLite IN operator processing, and collection extraction operations like `.firstOrNull()`.
**Action:** Always create and use a dedicated `getById(id)` method with an exact match query (`WHERE id = :id`) instead of reusing batch methods with single-element lists.
## 2025-02-18 - Fix N+1 Query Bottleneck in Synchronization Loops
**Learning:** In `UserSyncRepository.kt`, standard looping structures were querying the Room database inside loops (`getById` inside a loop), leading to an N+1 query performance bottleneck. Since `getByIds` already existed in `BookDao`, batching these queries was possible.
**Action:** When updating or synchronizing large datasets, always pre-fetch related entities outside the loop using chunked `IN` queries and cache them in a Map (`associateBy`) for fast O(1) in-memory lookups instead of individually hitting the database. Ensure chunks are <= 900 to adhere to SQLite variable limits.
