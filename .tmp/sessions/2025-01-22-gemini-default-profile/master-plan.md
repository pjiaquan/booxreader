# Master Plan: Gemini Default AI Profile

## Task Description
Implement automatic creation of a default Gemini AI profile when no profiles exist in the database.

## Architecture

### Current System State
- AI profiles are stored in Room database (`ai_profiles` table)
- No automatic default profile creation exists
- Users must manually create or import profiles
- App initializes AI profile sync in `BooxReaderApp.onCreate()`

### Proposed Architecture
1. **Detection Phase**: Check if AI profiles table is empty during app initialization
2. **Creation Phase**: If empty, create a default Gemini AI profile with placeholder API key
3. **Notification Phase**: Show user-friendly message about the default profile and how to get API key
4. **Default Selection**: Set the newly created profile as the active default profile

## Components (In Dependency Order)

### 1. Default Profile Generator
**File**: `app/src/main/java/my/hinoki/booxreader/data/repo/AiProfileDefaultGenerator.kt`

**Purpose**: Factory for creating default AI profiles

**Interface**:
```kotlin
class AiProfileDefaultGenerator {
    fun createGeminiDefaultProfile(): AiProfileEntity
}
```

**Responsibilities**:
- Generate a properly configured Gemini AI profile
- Use appropriate default settings for Gemini models
- Include placeholder API key with instructions

### 2. Profile Initialization Logic
**File**: Modify `app/src/main/java/my/hinoki/booxreader/data/repo/AiProfileRepository.kt`

**Interface**:
```kotlin
suspend fun ensureDefaultProfile(): Boolean
```

**Responsibilities**:
- Check if profiles table is empty
- If empty, create default Gemini profile
- Set as active profile
- Return true if profile was created, false otherwise

### 3. App Initialization Integration
**File**: Modify `app/src/main/java/my/hinoki/booxreader/BooxReaderApp.kt`

**Change**: Update `initializeAiProfileSync()` method

**Responsibilities**:
- Call `ensureDefaultProfile()` before sync
- Handle result gracefully (show toast if profile created)

### 4. User Notification Strings
**File**: Add to `app/src/main/res/values/strings.xml`

**Purpose**: User-facing messages for default profile creation

**Strings needed**:
- `ai_profile_default_created`: "Default Gemini profile created"
- `ai_profile_default_instruction`: "Get your API key at https://aistudio.google.com/app/apikey"

## Implementation Tasks

### Component 1: Default Profile Generator ✅
- [x] Create `AiProfileDefaultGenerator.kt` class
- [x] Implement `createGeminiDefaultProfile()` method
- [x] Configure Gemini-specific settings (model name, endpoint, system prompt)
- [x] Add placeholder API key with instructions

### Component 2: Repository Initialization ✅
- [x] Add `ensureDefaultProfile()` method to `AiProfileRepository`
- [x] Implement empty profile check using DAO
- [x] Integrate with `addProfile()` for creation
- [x] Integrate with `applyProfile()` for setting as default

### Component 3: App Initialization Integration ✅
- [x] Update `initializeAiProfileSync()` in `BooxReaderApp`
- [x] Add call to `ensureDefaultProfile()` before sync
- [x] Add user notification (Toast) when default profile is created

### Component 4: String Resources ✅
- [x] Add default profile creation message to `values/strings.xml`
- [x] Add API key instruction message to `values/strings.xml`
- [x] Add translated messages to `values-zh/strings.xml`
- [x] Add translated messages to `values-zh-rTW/strings.xml`

## Gemini AI Profile Configuration

```json
{
  "name": "Gemini",
  "modelName": "gemini-3-flash-preview",
  "apiKey": "<YOUR_GEMINI_API_KEY>",
  "serverBaseUrl": "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent",
  "systemPrompt": "You are a helpful AI assistant.",
  "userPromptTemplate": "%s",
  "useStreaming": true,
  "temperature": 0.7,
  "maxTokens": 8192,
  "topP": 1.0,
  "frequencyPenalty": 0.0,
  "presencePenalty": 0.0,
  "assistantRole": "model",
  "enableGoogleSearch": false,
  "extraParamsJson": null
}
```

## Notes

- The default profile uses `gemini-1.5-flash` which is fast and cost-effective
- API key placeholder will guide users to https://aistudio.google.com/app/apikey
- Default profile is only created on first launch when no profiles exist
- Subsequent app launches will not recreate the default profile
- The profile will sync to cloud for multi-device consistency

## Validation Criteria

- [x] Default profile is created on first app launch (no existing profiles)
- [x] Default profile is NOT created when profiles already exist
- [x] Created profile is automatically set as active
- [x] User sees notification when default profile is created
- [x] Profile syncs correctly to cloud
- [x] Profile can be edited by user to add real API key

## Implementation Status: ✅ ALL COMPONENTS COMPLETED
