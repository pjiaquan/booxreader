# BooxReader Database Schema Documentation

## Database Overview
- **Database Name**: boox_reader.db
- **Version**: 12 (Room Database)
- **Location**: `/data/data/my.hinoki.booxreader/databases/boox_reader.db`

## Tables

### 1. books
Stores book information and reading state.

| Column | Type | Description |
|--------|------|-------------|
| bookId | TEXT (PK) | Unique identifier for the book |
| title | TEXT | Book title |
| fileUri | TEXT | File path/URI of the book |
| lastLocatorJson | TEXT | JSON string of last reading position |
| lastOpenedAt | INTEGER | Timestamp when book was last opened |
| deleted | INTEGER | Boolean flag (0/1) for soft delete |
| deletedAt | INTEGER | Timestamp when book was deleted (null if not deleted) |

### 2. bookmarks
Stores bookmarks for books.

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER (PK, Autoincrement) | Local database ID |
| remoteId | TEXT | Remote ID (Firestore) for sync |
| bookId | TEXT | Foreign key to books.bookId |
| locatorJson | TEXT | JSON string of bookmark location |
| createdAt | INTEGER | Timestamp when bookmark was created |
| isSynced | INTEGER | Boolean flag (0/1) for sync status |
| updatedAt | INTEGER | Timestamp when bookmark was last updated |

### 3. ai_notes
Stores AI-generated notes and responses.

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER (PK, Autoincrement) | Local database ID |
| remoteId | TEXT | Remote ID (Firestore) for sync |
| bookId | TEXT | Foreign key to books.bookId (optional) |
| bookTitle | TEXT | Title of the book (denormalized) |
| originalText | TEXT | Original text/selection |
| aiResponse | TEXT | AI-generated response |
| locatorJson | TEXT | JSON string of text location (for highlighting) |
| createdAt | INTEGER | Timestamp when note was created |
| updatedAt | INTEGER | Timestamp when note was last updated |

### 4. users
Stores user information.

| Column | Type | Description |
|--------|------|-------------|
| userId | TEXT (PK) | User ID (from backend/Firebase) |
| email | TEXT | User email |
| displayName | TEXT | Display name |
| avatarUrl | TEXT | Avatar image URL |

### 5. ai_profiles
Stores AI configuration profiles.

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER (PK, Autoincrement) | Local database ID |
| name | TEXT | Profile name (e.g., "DeepSeek Chat") |
| modelName | TEXT | AI model name |
| apiKey | TEXT | API key for the AI service |
| serverBaseUrl | TEXT | Base URL for AI server |
| systemPrompt | TEXT | System prompt for AI |
| userPromptTemplate | TEXT | Template for user prompts |
| useStreaming | INTEGER | Boolean flag (0/1) for streaming responses |
| temperature | REAL | Temperature parameter (0.0-2.0, default 0.7) |
| maxTokens | INTEGER | Maximum tokens for response (default 4096) |
| topP | REAL | Top-P parameter (0.0-1.0, default 1.0) |
| frequencyPenalty | REAL | Frequency penalty (default 0.0) |
| presencePenalty | REAL | Presence penalty (default 0.0) |
| assistantRole | TEXT | Assistant role (default "assistant") |
| enableGoogleSearch | INTEGER | Boolean flag (0/1) for Google search (default 1) |
| remoteId | TEXT | Remote ID (Firebase) for sync |
| createdAt | INTEGER | Timestamp when profile was created |
| updatedAt | INTEGER | Timestamp when profile was last updated |
| isSynced | INTEGER | Boolean flag (0/1) for sync status |

## Relationships

### Logical Foreign Keys (not enforced in SQLite)
- `bookmarks.bookId` → `books.bookId`
- `ai_notes.bookId` → `books.bookId`

## Migration History

The database has undergone 12 migrations:
1. **v3→v4**: Added `users` table
2. **v4→v5**: Added `ai_notes.bookTitle`
3. **v5→v6**: Added `ai_notes.remoteId` and `ai_notes.updatedAt`
4. **v6→v7**: Added `bookmarks.remoteId` and `bookmarks.updatedAt`
5. **v7→v8**: Added `ai_profiles` table
6. **v8→v9**: Added AI generation parameters to `ai_profiles`
7. **v9→v10**: Added `ai_profiles.assistantRole`
8. **v10→v11**: Added `ai_profiles.enableGoogleSearch`
9. **v11→v12**: Added soft delete to `books` (deleted, deletedAt)

## Data Types Notes
- **TEXT**: Used for strings and JSON data
- **INTEGER**: Used for timestamps (milliseconds since epoch) and boolean flags (0/1)
- **REAL**: Used for floating-point numbers (AI parameters)
- **BOOLEAN values**: Stored as INTEGER (0 = false, 1 = true)

## JSON Fields
- `lastLocatorJson`: Contains reading position information
- `locatorJson`: Contains bookmark/highlight position information
- These fields likely contain CFI (EPUB) or page/location information for PDFs