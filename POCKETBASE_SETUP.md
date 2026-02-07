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
- ✅ Create all 7 collections
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

✨ Setup complete!
   Created: 7 new collections
```

#### Option B: Manual Creation (For reference)

Follow the detailed instructions in [`pocketbase_schema.md`](./pocketbase_schema.md) to manually create each collection through the admin UI.

**Collections to create:**
1. `settings` - 25 fields - User settings and preferences
2. `progress` - 5 fields - Reading progress per book
3. `bookmarks` - 5 fields - User bookmarks
4. `ai_notes` - 9 fields - AI conversation notes
5. `ai_profiles` - 17 fields - AI configuration profiles
6. `books` - 8 fields - Book metadata
7. `crash_reports` - 7 fields - Crash reports (optional)

### Step 3: Verify Setup

After creating all collections, verify:

1. ✅ All 7 collections are created
2. ✅ Each collection has a `user` relation field (except crash_reports)
3. ✅ API rules are set correctly (users can only access their own data)
4. ✅ Unique indexes are created where specified

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

## Files

- [`setup_pocketbase.py`](./setup_pocketbase.py) - **Automated setup script** (recommended)
- [`pocketbase_schema.md`](./pocketbase_schema.md) - Detailed schema documentation
- [`pocketbase_collections.json`](./pocketbase_collections.json) - JSON schema reference
- This README

## Need Help?

- Review the [PocketBase Documentation](https://pocketbase.io/docs/)
- Check the implementation in `UserSyncRepository.kt`
- Review `walkthrough.md` for implementation details
