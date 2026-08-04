## 2025-02-12 - Memory limits on database operations
**Learning:** Room/SQLite has a limit on the number of variables in an IN clause (usually 999). Operating on large lists without chunking will crash with 'Too many SQL variables'.
**Action:** Always use `.chunked(900)` or similar when passing large collections to Room IN queries (e.g. `deleteByIds`, `getByIds`).
## 2025-03-05 - Avoid IN clauses for single-entity lookups
**Learning:** Room/SQLite incurs unnecessary overhead when using an `IN` clause (e.g. `getByIds(listOf(id))`) for single entity lookups, including list allocation, SQLite IN operator processing, and collection extraction operations like `.firstOrNull()`.
**Action:** Always create and use a dedicated `getById(id)` method with an exact match query (`WHERE id = :id`) instead of reusing batch methods with single-element lists.
## 2025-08-04 - N+1 Queries in UserSyncRepository
**Learning:** Checking for the existence of records using `db.bookDao().getById()` inside a `for` loop causes severe N+1 query performance bottlenecks during sync operations where the payload might contain hundreds of books.
**Action:** Always pre-fetch needed records before iterating and cache them into an in-memory `Map`. Be sure to chunk the batched query using `.chunked(900)` and to update the in-memory cache directly on insert or update so subsequent lookups reflect the latest state.
