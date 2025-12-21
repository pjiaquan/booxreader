# Android App Compatibility & Synchronization Guide

This document outlines the requirements to ensure full data compatibility and seamless synchronization between the BooxReader Web App and the Android App. Both applications share the same Supabase backend and data schemas.

## 1. Encryption & Security

**Critical**: API Keys are encrypted before being stored in the database. Both platforms must use the exact same encryption configuration to read/write keys.

*   **Algorithm**: AES (ECB Mode, PKCS7 Padding)
*   **Secret Key**: `BooxReaderAiKeysSyncSecret2024!!`
*   **Web Implementation**: Uses `crypto-js`.
    ```typescript
    // Encryption (Web)
    CryptoJS.AES.encrypt(input, key, { mode: CryptoJS.mode.ECB, padding: CryptoJS.pad.Pkcs7 }).toString();
    ```
*   **Android Requirement**: Must use `AES/ECB/PKCS5Padding` (PKCS7 and PKCS5 are compatible for AES block sizes) with the exact same key string.

## 2. Database Schemas

### A. Settings (`settings` table)

This table stores global user preferences.

*   **Primary Key**: `user_id` (One row per user).
*   **Sync Logic**: Upsert on `user_id`.
*   **Critical Fields**:
    *   `api_key`: **ENCRYPTED** string.
    *   `active_profile_id`:
        *   **Web**: Prefers `UUID` (string).
        *   **Android (Legacy)**: May use `Long` (number).
        *   **Requirement**: Android **MUST** handle receiving a UUID string in this field without crashing. Ideally, migrate Android to use UUID strings for profile IDs.
    *   `updated_at`: Stored as a timestamp (bigint/number) or ISO string. Web converts all DB timestamps to `number` (milliseconds) locally.

### B. AI Profiles (`ai_profiles` table)

Stores custom AI configurations (prompts, models, etc.).

*   **Primary Key**: `id` (UUID).
*   **Critical Fields**:
    *   `id`: UUID string.
    *   `api_key`: **ENCRYPTED** string.
    *   `updated_at` / `created_at`: ISO 8601 strings in DB.
*   **Mapping**:
    *   DB `model_name` -> Local `modelName`
    *   DB `server_base_url` -> Local `serverBaseUrl`
    *   DB `enable_google_search` -> Local `enableGoogleSearch`

### C. AI Notes (`ai_notes` table)

Stores AI-generated insights and chat history for specific books.

*   **Primary Key**: `id` (UUID).
*   **Critical Fields**:
    *   `book_id`: **CRITICAL**. This is the **MD5 Hash** of the book file content (or a consistent unique identifier derived from the book).
        *   **Requirement**: Android MUST generate the `book_id` exactly the same way (MD5 of file) for notes to show up on the correct book.
    *   `messages`: JSON Array (`jsonb`). Stores the full chat history (`ConversationTurn[]`).
        *   Structure: `[{ "role": "user"|"assistant", "content": "...", "timestamp": 1234567890 }]`
    *   `locator`: JSON Object or Stringified JSON. Represents the reading position (CFI/XPath) where the note was created.
        *   **Web**: Can handle both JSON object and stringified JSON.
        *   **Android**: Should ensure it writes consistent locator formats (e.g., Readium CFI or similar standard used by the reader engine).

## 3. Synchronization Logic

*   **Realtime**: Both apps should subscribe to `postgres_changes` on `settings`, `ai_profiles`, and `ai_notes` tables, filtered by `user_id=eq.{currentUserId}`.
*   **Conflict Resolution**: Last Write Wins (LWW) based on `updated_at`.
*   **Timestamps**:
    *   Web app converts incoming ISO strings from Supabase (`2023-01-01T12:00:00Z`) to local millisecond timestamps (`1672574400000`) for sorting and logic.
    *   Android should ensure it sends valid ISO 8601 strings for `created_at` and `updated_at` columns when pushing data.

## 4. Checklist for Android Developer

1.  [ ] **Verify Encryption**: Unit test the AES encryption/decryption against a sample string encrypted by the web app.
2.  [ ] **Profile IDs**: Ensure the app doesn't crash if `active_profile_id` in `settings` is a string (UUID) instead of a number.
3.  [ ] **Book ID Hashing**: Confirm `book_id` generation uses standard MD5 of the file content.
4.  [ ] **Chat History**: Support reading/writing the `messages` JSONB column for multi-turn conversations.
