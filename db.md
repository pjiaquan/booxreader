# Supabase Database Schema (PostgreSQL)

This document defines the schema for the `progress` and `settings` tables in Supabase, incorporating Row Level Security (RLS) and user-based access control.

## 1. progress Table
Tracks reading progress per user and book.

```sql
-- Progress table
CREATE TABLE public.progress (
    id SERIAL PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE DEFAULT auth.uid(),
    book_id TEXT NOT NULL,
    book_title TEXT,
    locator_json TEXT NOT NULL, -- Readium Locator JSON
    updated_at BIGINT NOT NULL,  -- Timestamp in milliseconds (Android compatible)
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Ensure one progress record per user per book
    UNIQUE(user_id, book_id)
);

-- Security
ALTER TABLE public.progress ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage their own progress" ON public.progress
    FOR ALL USING (auth.uid() = user_id);
```

## 2. settings Table
Stores application preferences and AI profile configuration.

```sql
-- Settings table
CREATE TABLE public.settings (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE DEFAULT auth.uid(),
    
    -- UI & E-Ink Settings
    page_tap_enabled BOOLEAN DEFAULT TRUE,
    page_swipe_enabled BOOLEAN DEFAULT TRUE,
    page_animation_enabled BOOLEAN DEFAULT FALSE,
    boox_batch_refresh BOOLEAN DEFAULT TRUE,
    boox_fast_mode BOOLEAN DEFAULT TRUE,
    contrast_mode INTEGER DEFAULT 0,
    language TEXT DEFAULT 'system',
    
    -- AI Configuration
    server_base_url TEXT DEFAULT 'https://api.deepseek.com',
    api_key TEXT,
    ai_model_name TEXT DEFAULT 'deepseek-chat',
    ai_system_prompt TEXT,
    ai_user_prompt_template TEXT,
    
    -- AI Parameters
    temperature DECIMAL DEFAULT 0.7,
    max_tokens INTEGER DEFAULT 4096,
    top_p DECIMAL DEFAULT 1.0,
    frequency_penalty DECIMAL DEFAULT 0.0,
    presence_penalty DECIMAL DEFAULT 0.0,
    assistant_role TEXT DEFAULT 'assistant',
    enable_google_search BOOLEAN DEFAULT TRUE,
    use_streaming BOOLEAN DEFAULT FALSE,
    
    -- Sync Metadata
    active_profile_id TEXT, -- References the remoteId of an ai_profile
    updated_at BIGINT NOT NULL DEFAULT (extract(epoch from now()) * 1000),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Security
ALTER TABLE public.settings ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage their own settings" ON public.settings
    FOR ALL USING (auth.uid() = user_id);
```

## Implementation Notes
- **User Identification**: Uses Supabase's built-in `auth.users` for foreign key relationships.
- **Timestamps**: `updated_at` uses `BIGINT` (milliseconds) to maintain consistency with the Android app's `System.currentTimeMillis()`.
- **Concurrency**: The `progress` table uses a unique constraint on `(user_id, book_id)` to allow for efficient upsert operations.
