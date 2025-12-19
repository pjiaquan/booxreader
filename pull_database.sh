#!/bin/bash

# BooxReader Database Pull Script
# This script helps you pull the database from an Android device

set -e

echo "📚 BooxReader Database Export Tool"
echo "=================================="

# Check if ADB is installed
if ! command -v adb &> /dev/null; then
    echo "❌ Error: ADB (Android Debug Bridge) is not installed."
    echo "Please install Android SDK Platform Tools first."
    exit 1
fi

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected."
    echo "Please connect your device and enable USB debugging."
    exit 1
fi

echo "✅ Device found!"

# Package name
PACKAGE="my.hinoki.booxreader"

# Database paths
DB_PATH="/data/data/$PACKAGE/databases/boox_reader.db"
DB_WAL_PATH="/data/data/$PACKAGE/databases/boox_reader.db-wal"
DB_SHM_PATH="/data/data/$PACKAGE/databases/boox_reader.db-shm"

# Output files
OUTPUT_DB="boox_reader.db"
OUTPUT_DIR="database_export_$(date +%Y%m%d_%H%M%S)"

echo ""
echo "Pulling database from device..."

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Check if app is installed
if ! adb shell pm list packages | grep -q "$PACKAGE"; then
    echo "❌ BooxReader app is not installed on the device."
    exit 1
fi

# Pull the main database file
echo "📥 Pulling main database..."
adb shell "run-as $PACKAGE cat '$DB_PATH'" > "$OUTPUT_DIR/$OUTPUT_DB"

# Pull WAL and SHM files if they exist
if adb shell "run-as $PACKAGE test -f '$DB_WAL_PATH'" 2>/dev/null; then
    echo "📥 Pulling WAL file..."
    adb shell "run-as $PACKAGE cat '$DB_WAL_PATH'" > "$OUTPUT_DIR/boox_reader.db-wal"
fi

if adb shell "run-as $PACKAGE test -f '$DB_SHM_PATH'" 2>/dev/null; then
    echo "📥 Pulling SHM file..."
    adb shell "run-as $PACKAGE cat '$DB_SHM_PATH'" > "$OUTPUT_DIR/boox_reader.db-shm"
fi

# Verify the database
if [ -f "$OUTPUT_DIR/$OUTPUT_DB" ]; then
    SIZE=$(wc -c < "$OUTPUT_DIR/$OUTPUT_DB")
    echo "✅ Database pulled successfully!"
    echo "📊 Database size: $SIZE bytes"

    # Quick check if it's a valid SQLite database
    if command -v sqlite3 &> /dev/null; then
        echo ""
        echo "📋 Database overview:"
        cd "$OUTPUT_DIR"
        sqlite3 "$OUTPUT_DB" ".tables"
        echo ""
        echo "📊 Record counts:"
        sqlite3 "$OUTPUT_DB" "SELECT 'books: ' || COUNT(*) FROM books;
                               SELECT 'bookmarks: ' || COUNT(*) FROM bookmarks;
                               SELECT 'ai_notes: ' || COUNT(*) FROM ai_notes;
                               SELECT 'users: ' || COUNT(*) FROM users;
                               SELECT 'ai_profiles: ' || COUNT(*) FROM ai_profiles;"
        cd ..
    else
        echo ""
        echo "ℹ️  Install sqlite3 to view database details"
    fi

    echo ""
    echo "📁 Files saved in: $OUTPUT_DIR/"
    echo ""
    echo "🚀 Next steps:"
    echo "1. Run: cd $OUTPUT_DIR"
    echo "2. Run: python3 ../export_database.py $OUTPUT_DB"
    echo "3. Check the generated SQL files:"
    echo "   - booxreader_schema.sql (table definitions)"
    echo "   - booxreader_data.sql (all data as INSERT statements)"
    echo "   - booxreader_supabase.sql (combined for Supabase)"
    echo "   - booxreader_supabase_recommendations.md (setup guide)"

else
    echo "❌ Failed to pull database!"
    echo "Make sure USB debugging is enabled and the app has granted permissions."
    exit 1
fi