## 2025-02-12 - Memory limits on database operations
**Learning:** Room/SQLite has a limit on the number of variables in an IN clause (usually 999). Operating on large lists without chunking will crash with 'Too many SQL variables'.
**Action:** Always use `.chunked(900)` or similar when passing large collections to Room IN queries (e.g. `deleteByIds`, `getByIds`).
## 2025-03-05 - Avoid IN clauses for single-entity lookups
**Learning:** Room/SQLite incurs unnecessary overhead when using an `IN` clause (e.g. `getByIds(listOf(id))`) for single entity lookups, including list allocation, SQLite IN operator processing, and collection extraction operations like `.firstOrNull()`.
**Action:** Always create and use a dedicated `getById(id)` method with an exact match query (`WHERE id = :id`) instead of reusing batch methods with single-element lists.
## 2026-08-06 - Pre-fetching BookEntities
**Learning:** The implementation of 'getByIds' in BookDao.kt already exists and handles 'deleted=0' correctly for chunked pre-fetches.
**Action:** Confirmed that getByIds filters out soft-deleted entities just like getById.
