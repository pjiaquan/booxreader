# Component: Repository Initialization

## Purpose
Add logic to `AiProfileRepository` to check for empty profiles and create default if needed.

## Interface Addition

```kotlin
suspend fun ensureDefaultProfile(): Boolean
```

Returns:
- `true` - Default profile was created
- `false` - Profiles already exist (no action taken)

## Implementation Plan

Add to `AiProfileRepository.kt`:

1. Import `AiProfileDefaultGenerator`
2. Add instance variable: `private val defaultGenerator = AiProfileDefaultGenerator()`
3. Implement `ensureDefaultProfile()` method:
   - Check if any profiles exist using `dao.getAllList()`
   - If empty:
     - Create default Gemini profile
     - Add to database via `addProfile()`
     - Apply as active profile via `applyProfile()`
     - Return `true`
   - If not empty:
     - Return `false`

## Code Structure

```kotlin
suspend fun ensureDefaultProfile(): Boolean = withContext(Dispatchers.IO) {
    val profiles = dao.getAllList()

    if (profiles.isEmpty()) {
        // No profiles exist, create default
        val defaultProfile = defaultGenerator.createGeminiDefaultProfile()
        val saved = addProfile(defaultProfile)
        applyProfile(saved.id)
        return@withContext true
    }

    // Profiles already exist, do nothing
    return@withContext false
}
```

## Tasks

1. ✅ Read current `AiProfileRepository.kt`
2. ✅ Add import for `AiProfileDefaultGenerator`
3. ✅ Add `defaultGenerator` instance variable
4. ✅ Add `ensureDefaultProfile()` method
5. ✅ Validate code compiles

## Validation Criteria

- [x] Method added to `AiProfileRepository`
- [x] Returns `true` when profile is created
- [x] Returns `false` when profiles already exist
- [x] Created profile is set as active
- [x] Code compiles without errors

## Status: COMPLETED ✅

## Notes

- Uses existing DAO methods: `getAllList()`, `addProfile()`, `applyProfile()`
- Runs in IO thread context for database operations
- Pure functional approach - checks state, creates if needed
- Will sync to cloud automatically via `addProfile()` and `applyProfile()`
