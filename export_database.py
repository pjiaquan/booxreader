#!/usr/bin/env python3
"""
BooxReader Database Exporter
Exports the Android SQLite database to Supabase-compatible SQL
"""

import sqlite3
import json
import sys
import os
from datetime import datetime
from pathlib import Path

def export_table_to_sql(conn, table_name, output_file):
    """Export a table to SQL INSERT statements"""
    cursor = conn.cursor()

    # Get table schema
    cursor.execute(f"PRAGMA table_info({table_name})")
    columns = cursor.fetchall()
    column_names = [col[1] for col in columns]

    # Get all data
    cursor.execute(f"SELECT * FROM {table_name}")
    rows = cursor.fetchall()

    if not rows:
        print(f"-- No data in table {table_name}")
        return

    # Write INSERT statements
    for row in rows:
        # Convert Python values to SQL literals
        values = []
        for value in row:
            if value is None:
                values.append('NULL')
            elif isinstance(value, bool):
                values.append('1' if value else '0')
            elif isinstance(value, (int, float)):
                values.append(str(value))
            elif isinstance(value, str):
                # Escape single quotes and wrap in single quotes
                escaped = value.replace("'", "''")
                values.append(f"'{escaped}'")
            else:
                values.append(f"'{str(value)}'")

        insert_sql = f"INSERT INTO {table_name} ({', '.join(column_names)}) VALUES ({', '.join(values)});"
        output_file.write(insert_sql + '\n')

    print(f"-- Exported {len(rows)} rows from {table_name}")

def create_table_schemas():
    """Return the table schemas based on the entities"""
    schemas = {
        'books': """
-- Books table
CREATE TABLE books (
    bookId TEXT PRIMARY KEY,
    title TEXT,
    fileUri TEXT NOT NULL,
    lastLocatorJson TEXT,
    lastOpenedAt INTEGER NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0,
    deletedAt INTEGER
);
""",
        'bookmarks': """
-- Bookmarks table
CREATE TABLE bookmarks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    remoteId TEXT,
    bookId TEXT NOT NULL,
    locatorJson TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    isSynced INTEGER NOT NULL DEFAULT 0,
    updatedAt INTEGER NOT NULL
);
""",
        'ai_notes': """
-- AI Notes table
CREATE TABLE ai_notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    remoteId TEXT,
    bookId TEXT,
    bookTitle TEXT,
    originalText TEXT NOT NULL,
    aiResponse TEXT NOT NULL,
    locatorJson TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);
""",
        'users': """
-- Users table
CREATE TABLE users (
    userId TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    displayName TEXT,
    avatarUrl TEXT
);
""",
        'ai_profiles': """
-- AI Profiles table
CREATE TABLE ai_profiles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    modelName TEXT NOT NULL,
    apiKey TEXT NOT NULL,
    serverBaseUrl TEXT NOT NULL,
    systemPrompt TEXT NOT NULL,
    userPromptTemplate TEXT NOT NULL,
    useStreaming INTEGER NOT NULL,
    temperature REAL NOT NULL DEFAULT 0.7,
    maxTokens INTEGER NOT NULL DEFAULT 4096,
    topP REAL NOT NULL DEFAULT 1.0,
    frequencyPenalty REAL NOT NULL DEFAULT 0.0,
    presencePenalty REAL NOT NULL DEFAULT 0.0,
    assistantRole TEXT NOT NULL DEFAULT 'assistant',
    enableGoogleSearch INTEGER NOT NULL DEFAULT 1,
    remoteId TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    isSynced INTEGER NOT NULL DEFAULT 0
);
"""
    }
    return schemas

def convert_to_supabase_schema(input_sql_file, output_sql_file):
    """Convert the SQLite schema to Supabase/PostgreSQL format"""
    with open(input_sql_file, 'r') as f:
        content = f.read()

    # PostgreSQL specific modifications
    pg_content = content.replace(
        'INTEGER PRIMARY KEY AUTOINCREMENT',
        'SERIAL PRIMARY KEY'
    ).replace(
        'INTEGER NOT NULL',
        'BIGINT NOT NULL'
    ).replace(
        'TEXT NOT NULL',
        'TEXT NOT NULL'
    ).replace(
        'REAL NOT NULL',
        'DECIMAL NOT NULL'
    ).replace(
        'DEFAULT 0',
        "DEFAULT FALSE"
    ).replace(
        'DEFAULT 1',
        "DEFAULT TRUE"
    )

    # Add PostgreSQL-specific headers
    pg_header = """-- BooxReader Database Schema for Supabase/PostgreSQL
-- Generated on: {timestamp}
-- Note: This is the schema definition. For Supabase, you may want to:
-- 1. Add created_at and updated_at timestamp columns with default values
-- 2. Add foreign key constraints if needed
-- 3. Add indexes for performance
-- 4. Set up Row Level Security (RLS) policies

""".format(timestamp=datetime.now().isoformat())

    with open(output_sql_file, 'w') as f:
        f.write(pg_header)
        f.write(pg_content)

    print(f"Converted to Supabase format: {output_sql_file}")

def main():
    # Path to the Android device database
    # You need to pull this from your Android device first
    db_path = "boox_reader.db"

    if len(sys.argv) > 1:
        db_path = sys.argv[1]

    if not os.path.exists(db_path):
        print(f"Error: Database file '{db_path}' not found!")
        print("\nTo get the database from your Android device:")
        print("1. Connect your device via ADB")
        print("2. Run: adb shell 'run-as my.hinoki.booxreader cat /data/data/my.hinoki.booxreader/databases/boox_reader.db' > boox_reader.db")
        sys.exit(1)

    # Connect to database
    conn = sqlite3.connect(db_path)

    # Output files
    schema_file = "booxreader_schema.sql"
    data_file = "booxreader_data.sql"
    supabase_file = "booxreader_supabase.sql"

    # 1. Export schema
    print("Exporting database schema...")
    with open(schema_file, 'w') as f:
        f.write("-- BooxReader Database Schema\n")
        f.write(f"-- Generated on: {datetime.now().isoformat()}\n\n")

        schemas = create_table_schemas()
        for table_name, schema in schemas.items():
            f.write(schema)
            f.write("\n")

    print(f"Schema saved to: {schema_file}")

    # 2. Export data
    print("\nExporting data...")
    with open(data_file, 'w') as f:
        f.write("-- BooxReader Database Data\n")
        f.write(f"-- Generated on: {datetime.now().isoformat()}\n\n")

        # Get all table names
        cursor = conn.cursor()
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table'")
        tables = [row[0] for row in cursor.fetchall() if row[0] != 'android_metadata' and row[0] != 'sqlite_sequence']

        for table in tables:
            print(f"\nExporting table: {table}")
            export_table_to_sql(conn, table, f)

    print(f"\nData saved to: {data_file}")

    # 3. Create Supabase-compatible version
    print("\nCreating Supabase-compatible version...")
    with open(supabase_file, 'w') as f:
        f.write("-- BooxReader Database for Supabase\n")
        f.write(f"-- Generated on: {datetime.now().isoformat()}\n\n")

        # Write schema
        with open(schema_file, 'r') as schema_f:
            schema_content = schema_f.read()
            # Convert to PostgreSQL
            pg_content = schema_content.replace(
                'INTEGER PRIMARY KEY AUTOINCREMENT',
                'SERIAL PRIMARY KEY'
            )
            f.write(pg_content)
            f.write("\n")

        f.write("\n-- Data inserts\n")
        # Write data
        with open(data_file, 'r') as data_f:
            f.write(data_f.read())

    print(f"Supabase version saved to: {supabase_file}")

    # 4. Create additional recommendations for Supabase
    recommendations_file = "booxreader_supabase_recommendations.md"
    with open(recommendations_file, 'w') as f:
        f.write("""# BooxReader Supabase Setup Recommendations

## Database Schema

The exported SQL file creates the basic table structure. For Supabase, consider these enhancements:

### 1. Add Timestamps
```sql
ALTER TABLE books ADD COLUMN created_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE books ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW();

ALTER TABLE bookmarks ADD COLUMN created_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE bookmarks ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW();

ALTER TABLE ai_notes ADD COLUMN created_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE ai_notes ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW();

ALTER TABLE ai_profiles ADD COLUMN created_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE ai_profiles ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW();
```

### 2. Add Indexes
```sql
CREATE INDEX idx_books_deleted ON books(deleted);
CREATE INDEX idx_bookmarks_book_id ON bookmarks(bookId);
CREATE INDEX idx_ai_notes_book_id ON ai_notes(bookId);
CREATE INDEX idx_ai_notes_remote_id ON ai_notes(remoteId);
CREATE INDEX idx_bookmarks_remote_id ON bookmarks(remoteId);
```

### 3. Add Foreign Key Constraints (Optional)
```sql
ALTER TABLE bookmarks ADD CONSTRAINT fk_bookmarks_book
    FOREIGN KEY (bookId) REFERENCES books(bookId) ON DELETE CASCADE;

ALTER TABLE ai_notes ADD CONSTRAINT fk_ai_notes_book
    FOREIGN KEY (bookId) REFERENCES books(bookId) ON DELETE CASCADE;
```

### 4. Set Up Row Level Security (RLS)
```sql
-- Enable RLS on all tables
ALTER TABLE books ENABLE ROW LEVEL SECURITY;
ALTER TABLE bookmarks ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_profiles ENABLE ROW LEVEL SECURITY;

-- Example RLS policy for books
CREATE POLICY "Users can view their own books" ON books
    FOR SELECT USING (true);

CREATE POLICY "Users can insert their own books" ON books
    FOR INSERT WITH CHECK (true);

CREATE POLICY "Users can update their own books" ON books
    FOR UPDATE USING (true);
```

### 5. Data Type Considerations
- Consider using `UUID` instead of TEXT for IDs
- Use `JSONB` instead of TEXT for JSON fields (locatorJson)
- Use `TIMESTAMPTZ` for timestamp fields

### 6. Supabase Specific Features
- Set up authentication to link with the `users` table
- Consider using Supabase Realtime for live updates
- Set up storage buckets for file attachments if needed

## Import Steps
1. Create a new Supabase project
2. Run the SQL from `booxreader_supabase.sql` in the Supabase SQL editor
3. Apply the enhancements above as needed
4. Update your Android app to use Supabase client libraries
""")

    print(f"\nRecommendations saved to: {recommendations_file}")

    conn.close()
    print("\n✅ Export complete!")

if __name__ == "__main__":
    main()