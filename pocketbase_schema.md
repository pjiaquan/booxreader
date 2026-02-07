# PocketBase Schema Setup Guide

This document describes all the collections needed for the BooxReader app to sync with PocketBase.

## Collections Overview

You need to create **7 collections** in your PocketBase admin UI:

1. `settings` - User settings and preferences
2. `progress` - Reading progress per book
3. `bookmarks` - User bookmarks
4. `ai_notes` - AI conversation notes
5. `ai_profiles` - AI configuration profiles
6. `books` - Book metadata
7. `crash_reports` - Crash reports (optional)

---

## Collection 1: `settings`

**Type:** Base Collection

### Fields

| Field Name | Type | Required | Options |
|------------|------|----------|---------|
| `user` | Relation | ✅ | Related to `_pb_users_auth_` (Single) |
| `pageTapEnabled` | Bool | ❌ | Default: `true` |
| `pageSwipeEnabled` | Bool | ❌ | Default: `true` |
| `contrastMode` | Number | ❌ | Default: `0`, Min: 0, Max: 3 |
| `convertToTraditionalChinese` | Bool | ❌ | Default: `true` |
| `serverBaseUrl` | Text | ❌ | |
| `exportToCustomUrl` | Bool | ❌ | Default: `false` |
| `exportCustomUrl` | Text | ❌ | |
| `exportToLocalDownloads` | Bool | ❌ | Default: `false` |
| `apiKey` | Text | ❌ | |
| `aiModelName` | Text | ❌ | Default: `"deepseek-chat"` |
| `aiSystemPrompt` | Text | ❌ | |
| `aiUserPromptTemplate` | Text | ❌ | Default: `"%s"` |
| `temperature` | Number | ❌ | Default: `0.7` |
| `maxTokens` | Number | ❌ | Default: `4096` |
| `topP` | Number | ❌ | Default: `1.0` |
| `frequencyPenalty` | Number | ❌ | Default: `0.0` |
| `presencePenalty` | Number | ❌ | Default: `0.0` |
| `assistantRole` | Text | ❌ | Default: `"assistant"` |
| `enableGoogleSearch` | Bool | ❌ | Default: `true` |
| `useStreaming` | Bool | ❌ | Default: `false` |
| `pageAnimationEnabled` | Bool | ❌ | Default: `false` |
| `showPageIndicator` | Bool | ❌ | Default: `true` |
| `language` | Text | ❌ | Default: `"system"` |
| `activeProfileId` | Number | ❌ | Default: `-1` |
| `updatedAt` | Number | ❌ | |

### Indexes

- Create unique index on `user` field

### API Rules

- **List:** `@request.auth.id != "" && user = @request.auth.id`
- **View:** `@request.auth.id != "" && user = @request.auth.id`
- **Create:** `@request.auth.id != "" && user = @request.auth.id`
- **Update:** `@request.auth.id != "" && user = @request.auth.id`
- **Delete:** `@request.auth.id != "" && user = @request.auth.id`

---

## Collection 2: `progress`

**Type:** Base Collection

### Fields

| Field Name | Type | Required | Options |
|------------|------|----------|---------|
| `user` | Relation | ✅ | Related to `_pb_users_auth_` (Single) |
| `bookId` | Text | ✅ | |
| `bookTitle` | Text | ❌ | |
| `locatorJson` | Text | ✅ | |
| `updatedAt` | Number | ❌ | |

### Indexes

- Create unique index on `user` + `bookId`

### API Rules

- **List:** `@request.auth.id != "" && user = @request.auth.id`
- **View:** `@request.auth.id != "" && user = @request.auth.id`
- **Create:** `@request.auth.id != "" && user = @request.auth.id`
- **Update:** `@request.auth.id != "" && user = @request.auth.id`
- **Delete:** `@request.auth.id != "" && user = @request.auth.id`

---

## Collection 3: `bookmarks`

**Type:** Base Collection

### Fields

| Field Name | Type | Required | Options |
|------------|------|----------|---------|
| `user` | Relation | ✅ | Related to `_pb_users_auth_` (Single) |
| `bookId` | Text | ✅ | |
| `locatorJson` | Text | ✅ | |
| `createdAt` | Number | ❌ | |
| `updatedAt` | Number | ❌ | |

### API Rules

- **List:** `@request.auth.id != "" && user = @request.auth.id`
- **View:** `@request.auth.id != "" && user = @request.auth.id`
- **Create:** `@request.auth.id != "" && user = @request.auth.id`
- **Update:** `@request.auth.id != "" && user = @request.auth.id`
- **Delete:** `@request.auth.id != "" && user = @request.auth.id`

---

## Collection 4: `ai_notes`

**Type:** Base Collection

### Fields

| Field Name | Type | Required | Options |
|------------|------|----------|---------|
| `user` | Relation | ✅ | Related to `_pb_users_auth_` (Single) |
| `bookId` | Text | ❌ | |
| `bookTitle` | Text | ❌ | |
| `messages` | Text | ✅ | JSON string of conversation |
| `originalText` | Text | ❌ | |
| `aiResponse` | Text | ❌ | |
| `locatorJson` | Text | ❌ | |
| `createdAt` | Number | ❌ | |
| `updatedAt` | Number | ❌ | |

### API Rules

- **List:** `@request.auth.id != "" && user = @request.auth.id`
- **View:** `@request.auth.id != "" && user = @request.auth.id`
- **Create:** `@request.auth.id != "" && user = @request.auth.id`
- **Update:** `@request.auth.id != "" && user = @request.auth.id`
- **Delete:** `@request.auth.id != "" && user = @request.auth.id`

---

## Collection 5: `ai_profiles`

**Type:** Base Collection

### Fields

| Field Name | Type | Required | Options |
|------------|------|----------|---------|
| `user` | Relation | ✅ | Related to `_pb_users_auth_` (Single) |
| `name` | Text | ✅ | |
| `modelName` | Text | ✅ | |
| `apiKey` | Text | ✅ | |
| `serverBaseUrl` | Text | ✅ | |
| `systemPrompt` | Text | ❌ | |
| `userPromptTemplate` | Text | ❌ | |
| `useStreaming` | Bool | ❌ | Default: `false` |
| `temperature` | Number | ❌ | Default: `0.7` |
| `maxTokens` | Number | ❌ | Default: `4096` |
| `topP` | Number | ❌ | Default: `1.0` |
| `frequencyPenalty` | Number | ❌ | Default: `0.0` |
| `presencePenalty` | Number | ❌ | Default: `0.0` |
| `assistantRole` | Text | ❌ | Default: `"assistant"` |
| `enableGoogleSearch` | Bool | ❌ | Default: `true` |
| `extraParamsJson` | Text | ❌ | JSON string |
| `createdAt` | Number | ❌ | |
| `updatedAt` | Number | ❌ | |

### API Rules

- **List:** `@request.auth.id != "" && user = @request.auth.id`
- **View:** `@request.auth.id != "" && user = @request.auth.id`
- **Create:** `@request.auth.id != "" && user = @request.auth.id`
- **Update:** `@request.auth.id != "" && user = @request.auth.id`
- **Delete:** `@request.auth.id != "" && user = @request.auth.id`

---

## Collection 6: `books`

**Type:** Base Collection

### Fields

| Field Name | Type | Required | Options |
|------------|------|----------|---------|
| `user` | Relation | ✅ | Related to `_pb_users_auth_` (Single) |
| `bookId` | Text | ✅ | |
| `title` | Text | ❌ | |
| `storagePath` | Text | ❌ | S3/R2 path if uploaded |
| `fileHash` | Text | ❌ | For deduplication |
| `deleted` | Bool | ❌ | Default: `false` |
| `deletedAt` | Number | ❌ | |
| `updatedAt` | Number | ❌ | |

### Indexes

- Create unique index on `user` + `bookId`

### API Rules

- **List:** `@request.auth.id != "" && user = @request.auth.id`
- **View:** `@request.auth.id != "" && user = @request.auth.id`
- **Create:** `@request.auth.id != "" && user = @request.auth.id`
- **Update:** `@request.auth.id != "" && user = @request.auth.id`
- **Delete:** `@request.auth.id != "" && user = @request.auth.id`

---

## Collection 7: `crash_reports` (Optional)

**Type:** Base Collection

### Fields

| Field Name | Type | Required | Options |
|------------|------|----------|---------|
| `user` | Relation | ❌ | Related to `_pb_users_auth_` (Single) |
| `appVersion` | Text | ✅ | |
| `androidVersion` | Text | ❌ | |
| `deviceModel` | Text | ❌ | |
| `stackTrace` | Text | ✅ | |
| `message` | Text | ❌ | |
| `timestamp` | Number | ✅ | |

### API Rules

- **List:** Admin only
- **View:** Admin only
- **Create:** `@request.auth.id != ""` (any authenticated user)
- **Update:** Admin only
- **Delete:** Admin only

---

## Quick Setup Steps

1. **Access PocketBase Admin UI**
   - Navigate to `http://your-pocketbase-url/_/`
   - Login with admin credentials

2. **Create Each Collection**
   - Click "New Collection"
   - Choose "Base Collection"
   - Set collection name (e.g., `settings`)
   - Add fields as specified above
   - Set API rules for each operation
   - Save collection

3. **Test Authentication**
   - Ensure user authentication is working
   - Test creating a record in one collection
   - Verify API rules are enforced

4. **Verify in App**
   - Update `.env` with `POCKETBASE_URL`
   - Build and run the app
   - Test sync operations

---

## Notes

- All `Number` fields for timestamps use Unix milliseconds (Long in Kotlin)
- All `Relation` fields link to the built-in `_pb_users_auth_` collection
- API rules ensure users can only access their own data
- The `user` field should be set automatically from `@request.auth.id`
