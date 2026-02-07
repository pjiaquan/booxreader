# Quick Start: Automated PocketBase Setup

## 1. Install Dependencies

```bash
pip3 install requests
```

## 2. Run Setup Script

```bash
python3 setup_pocketbase.py \
  --url http://your-pocketbase-url \
  --email admin@example.com \
  --password your-admin-password
```

## 3. That's it!

The script will create all 7 collections automatically:
- ✅ settings
- ✅ progress
- ✅ bookmarks
- ✅ ai_notes
- ✅ ai_profiles
- ✅ books
- ✅ crash_reports

## Verify

Visit your PocketBase admin UI at `http://your-pocketbase-url/_/` to verify all collections were created.

## Next Steps

1. Update your `.env` with `POCKETBASE_URL=http://your-pocketbase-url`
2. Build the app: `./gradlew :app:assembleDebug`
3. Test sync operations

---

For detailed documentation, see:
- [`POCKETBASE_SETUP.md`](./POCKETBASE_SETUP.md) - Complete setup guide
- [`pocketbase_schema.md`](./pocketbase_schema.md) - Schema details
- [`setup_pocketbase.py`](./setup_pocketbase.py) - The setup script
