# PocketBase Setup Instructions

This guide will help you set up PocketBase for the BooxReader app.

## Quick Start

### Step 1: Access PocketBase Admin UI

1. Start your PocketBase server (if not already running)
2. Open your browser and navigate to: `http://your-pocketbase-url/_/`
3. Login with your admin credentials

### Step 2: Create Collections

You have **two options** to create the required collections:

#### Option A: Automated Setup (Recommended) ⚡

Use the provided Python script to create all collections automatically:

```bash
# Install required package
pip3 install requests

# Run the setup script
python3 setup_pocketbase.py \
  --url http://your-pocketbase-url \
  --email admin@example.com \
  --password your-admin-password
```

The script will:
- ✅ Authenticate as admin
- ✅ Create all core collections (and auto-add required new ones)
- ✅ Set up proper schemas, indexes, and API rules
- ✅ Skip collections that already exist
- ✅ Show progress and results

**Example output:**
```
🚀 Starting PocketBase collection setup...
🔐 Authenticating as admin...
✅ Authentication successful

📦 Creating collections...
✅ Created collection: settings
✅ Created collection: progress
✅ Created collection: bookmarks
✅ Created collection: ai_notes
✅ Created collection: ai_profiles
✅ Created collection: books
✅ Created collection: crash_reports
✅ Created collection: qdrant_sync_logs
✅ Created collection: documents
✅ Created collection: chunks
✅ Created collection: embeddings

✨ Setup complete!
   Created: 11 new collections
```

#### Option B: Manual Creation (For reference)

Follow the detailed instructions in [`pocketbase_schema.md`](./pocketbase_schema.md) to manually create each collection through the admin UI.

**Collections to create (core):**
1. `settings` - 25 fields - User settings and preferences
2. `progress` - 5 fields - Reading progress per book
3. `bookmarks` - 5 fields - User bookmarks
4. `ai_notes` - 9 fields - AI conversation notes
5. `ai_profiles` - 17 fields - AI configuration profiles
6. `books` - 9 fields - Book metadata + EPUB file
7. `crash_reports` - 7 fields - Crash reports (optional)
8. `qdrant_sync_logs` - Qdrant hook audit log (optional)

**Optional RAG collections (PocketBase-native embeddings):**
1. `documents`
2. `chunks`
3. `embeddings`

### Step 3: Verify Setup

After creating all collections, verify:

1. ✅ All required collections are created
2. ✅ Each collection has a `user` relation field (except crash_reports)
3. ✅ API rules are set correctly (users can only access their own data)
4. ✅ Unique indexes are created where specified
5. ✅ `books.bookFile` (File, single) exists for cross-device EPUB download

If you plan to use PocketBase-native semantic retrieval, also verify:
- ✅ `documents/chunks/embeddings` exist
- ✅ Hook routes respond:
  - `POST /boox-rag-upsert`
  - `POST /boox-rag-search`

### Step 4: Configure App

1. Update your `.env` file with the PocketBase URL:
   ```
   POCKETBASE_URL=http://your-pocketbase-server
   ```

2. Rebuild the app:
   ```bash
   ./gradlew :app:assembleDebug
   ```

### Step 5: Test

1. Launch the app
2. Sign in with a test account
3. Try creating:
   - A bookmark
   - An AI note
   - Update settings
4. Check the PocketBase admin UI to verify records are created

### Step 6: Enable Server-Side Mail Queue Hook (Required for daily summary email)

The app may enqueue daily summary emails into `mail_queue` (instead of direct `/api/mails/send`).
To actually deliver those queued emails, add this PocketBase hook on the server:

1. Copy `pb_hooks/20_mail_queue_sender.pb.js` from this repo to your PocketBase server's `pb_hooks/` directory.
2. Ensure SMTP is configured in PocketBase Admin:
   - `Settings -> Mail settings`
3. Restart PocketBase.
4. Create a test queue record (or tap "Email Daily Summary" in app) and verify:
   - `mail_queue.status` becomes `sent`
   - mail arrives in inbox/spam folder

## Common Issues

### Issue: "Unresolved reference: POCKETBASE_URL"

**Solution:** Add `POCKETBASE_URL` to your `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "POCKETBASE_URL", "\"${project.findProperty("POCKETBASE_URL") ?: "http://localhost:8090"}\"")
```

### Issue: "403 Forbidden" or "401 Unauthorized"

**Solution:** Check API rules in collection settings. Ensure:
- List/View/Create/Update/Delete rules include: `@request.auth.id != ""`
- User relation is properly set

### Issue: Records not syncing

**Solution:**
1. Check device logs for errors
2. Verify PocketBase URL is correct
3. Ensure user is authenticated
4. Check network connectivity

### Issue: Books show on other devices but cannot be opened

**Cause:** Existing `books` collection was created without a `bookFile` file field, so EPUB binaries were never uploaded.

**Solution:**
1. In PocketBase Admin, open collection `books`
2. Add field `bookFile` with type `File`
3. Set `Max files` to `1`
4. Add `application/epub+zip` to allowed MIME types
5. Save schema changes
6. Re-open books once on source device so files upload, then sync on target device

## Files

- [`setup_pocketbase.py`](./setup_pocketbase.py) - **Automated setup script** (recommended)
- [`pocketbase_schema.md`](./pocketbase_schema.md) - Detailed schema documentation
- [`pocketbase_collections.json`](./pocketbase_collections.json) - JSON schema reference
- This README

## Schema sync workflow

The setup script now doubles as a schema snapshot tool so you can pull the live schema, keep it under version control, and reapply it later:

1. Export the schema from the server you want to mirror:

   ```bash
   python3 setup_pocketbase.py \
     --url http://your-pocketbase-url \
     --email admin@example.com \
     --password your-admin-password \
     --pull-schema
   ```

   By default this writes `pocketbase_collections.json`. Pass `--pull-schema custom-schema.json` to change the filename.

2. Commit the exported JSON and treat it as your canonical schema. When you want to update PocketBase, edit the file locally (e.g., add/remove fields, change rules).

3. Apply the edited schema back to any server:

   ```bash
   python3 setup_pocketbase.py \
     --url http://your-pocketbase-url \
     --email admin@example.com \
     --password your-admin-password \
     --schema-file pocketbase_collections.json
   ```

   If the file is missing, the script still falls back to the built-in schema definition.

## Need Help?

- Review the [PocketBase Documentation](https://pocketbase.io/docs/)
- Check the implementation in `UserSyncRepository.kt`
- Review `walkthrough.md` for implementation details
