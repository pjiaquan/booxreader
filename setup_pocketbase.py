#!/usr/bin/env python3
"""
PocketBase Collection Setup Script

This script automatically creates all required collections for the BooxReader app.
It uses the PocketBase Admin API to create collections programmatically.

Usage:
    python3 setup_pocketbase.py --url http://localhost:8090 --email admin@example.com --password yourpassword

Requirements:
    pip install requests
"""

import argparse
import requests
import json
import sys


def authenticate_admin(base_url, email, password, verify_ssl=True):
    """Authenticate as superuser or admin and return the auth token."""
    
    # Try with 'email' field first (newer PocketBase versions)
    payload_email = {
        "email": email,
        "password": password
    }
    
    # Fallback to 'identity' field (older versions)
    payload_identity = {
        "identity": email,
        "password": password
    }
    
    auth_endpoints = [
        f"{base_url}/api/collections/_superusers/auth-with-password",
        f"{base_url}/api/admins/auth-with-password",
    ]

    for endpoint_index, auth_url in enumerate(auth_endpoints, 1):
        print(f"   Endpoint {endpoint_index}: {auth_url}")

        # Try with 'email' field first
        for attempt, payload in enumerate([payload_email, payload_identity], 1):
            field_name = "email" if attempt == 1 else "identity"
            print(f"   Attempt {attempt}: Using '{field_name}' field...")

            try:
                response = requests.post(
                    auth_url,
                    json=payload,
                    verify=verify_ssl,
                    headers={"Content-Type": "application/json"}
                )

                print(f"   Status: {response.status_code}")

                if response.status_code == 200:
                    data = response.json()
                    token = data.get("token") or data.get("record", {}).get("token")

                    if not token:
                        print(f"❌ No token in response: {data}")
                        sys.exit(1)

                    return token
                elif response.status_code == 400 and attempt == 1:
                    # Try next attempt with 'identity' field
                    print(f"   ⚠️  Failed with '{field_name}', trying alternative...")
                    continue
                else:
                    # Last attempt failed or non-400 error
                    response.raise_for_status()

            except requests.exceptions.RequestException as e:
                if attempt == 2:
                    # Try next endpoint after identity attempt
                    print(f"   ⚠️  Auth attempt failed: {e}")
                    if hasattr(e, 'response') and e.response is not None:
                        print(f"   Response: {e.response.text}")
                    break

    print(f"❌ Authentication failed with both superuser and admin endpoints")
    sys.exit(1)


def create_collection(base_url, token, collection_data):
    """Create a single collection in phases: fields, user relation, rules, indexes."""
    collections_url = f"{base_url}/api/collections"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    
    collection_name = collection_data.get("name")
    all_fields = collection_data.get("fields", [])
    
    # Separate relation fields from regular fields
    regular_fields = []
    relation_fields = []
    
    for field in all_fields:
        if field.get("type") == "relation":
            relation_fields.append(field)
        else:
            regular_fields.append(field)
    
    def get_existing_collection():
        response = requests.get(collections_url, headers=headers)
        response.raise_for_status()
        data = response.json()
        collections = data.get("items", data) if isinstance(data, dict) else data
        for c in collections:
            if c.get("name") == collection_name:
                return c
        return None

    def normalize_relation_field(field):
        if field.get("name") == "user" and field.get("options", {}).get("collectionId") == "_pb_users_auth_":
            field_copy = field.copy()
            field_copy["options"] = field.get("options", {}).copy()
            field_copy["options"]["collectionId"] = "users"
            return field_copy
        return field

    def merge_fields(existing_fields, desired_fields):
        existing_by_name = {f.get("name"): f for f in existing_fields}
        merged = list(existing_fields)
        added = 0
        for f in desired_fields:
            name = f.get("name")
            if not name or name in existing_by_name:
                continue
            merged.append(normalize_relation_field(f))
            added += 1
        return merged, added

    collection_id = None
    current_fields = []

    # Phase 1: Create collection with non-relation fields only (or load existing)
    collection_bare = {
        "name": collection_data.get("name"),
        "type": collection_data.get("type"),
        "fields": regular_fields,  # Only non-relation fields
        "listRule": None,
        "viewRule": None,
        "createRule": None,
        "updateRule": None,
        "deleteRule": None
    }

    print(f"   Creating with {len(regular_fields)} regular fields (+ {len(relation_fields)} relation fields to add later)")

    try:
        response = requests.post(collections_url, headers=headers, json=collection_bare)

        if response.status_code == 400:
            error_data = response.json() if response.text else {}
            if "already exists" in str(error_data).lower() or "name_exists" in str(error_data):
                existing = get_existing_collection()
                if not existing:
                    print(f"❌ Failed to load existing collection '{collection_name}'")
                    return False
                collection_id = existing.get("id")
                current_fields = existing.get("fields", [])
                print(f"⚠️  Collection '{collection_name}' already exists, updating rules/fields/indexes...")
            else:
                print(f"   Error response: {response.text}")

        if collection_id is None:
            response.raise_for_status()
            created_collection = response.json()
            collection_id = created_collection.get("id")
            current_fields = regular_fields.copy()
            print(f"✅ Created collection: {collection_name}")

        # Phase 2: Add relation fields and any missing fields
        update_url = f"{collections_url}/{collection_id}"
        desired_fields = regular_fields + relation_fields
        merged_fields, added_fields = merge_fields(current_fields, desired_fields)
        if added_fields > 0:
            fields_update = {"fields": merged_fields}
            try:
                update_response = requests.patch(update_url, headers=headers, json=fields_update)
                update_response.raise_for_status()
                print(f"   ✅ Added {added_fields} missing field(s)")
            except requests.exceptions.RequestException as e:
                print(f"   ⚠️  Warning: Failed to add missing fields: {e}")
                if hasattr(e, 'response') and e.response and e.response.text:
                    error_msg = e.response.text[:300]
                    print(f"      {error_msg}")

        # Phase 3: Update with API rules
        if collection_data.get("listRule") is not None or collection_data.get("createRule"):
            rules_update = {
                "listRule": collection_data.get("listRule"),
                "viewRule": collection_data.get("viewRule"),
                "createRule": collection_data.get("createRule"),
                "updateRule": collection_data.get("updateRule"),
                "deleteRule": collection_data.get("deleteRule")
            }

            try:
                update_response = requests.patch(update_url, headers=headers, json=rules_update)
                update_response.raise_for_status()
                print(f"   ✅ Added API rules")
            except requests.exceptions.RequestException as e:
                print(f"   ⚠️  Warning: Failed to add API rules: {e}")
                if hasattr(e, 'response') and e.response is not None:
                    try:
                        error_text = e.response.text
                    except Exception:
                        error_text = None
                    if error_text:
                        print(f"      {error_text}")

        # Phase 4: Update with indexes
        if collection_data.get("indexes"):
            indexes_update = {
                "indexes": collection_data.get("indexes")
            }

            try:
                update_response = requests.patch(update_url, headers=headers, json=indexes_update)
                update_response.raise_for_status()
                print(f"   ✅ Added indexes")
            except requests.exceptions.RequestException as e:
                print(f"   ⚠️  Warning: Failed to add indexes: {e}")
                if hasattr(e, 'response') and e.response is not None:
                    try:
                        error_text = e.response.text
                    except Exception:
                        error_text = None
                    if error_text:
                        print(f"      {error_text}")

        return True

    except requests.exceptions.RequestException as e:
        print(f"❌ Failed to create/update collection '{collection_name}': {e}")
        if hasattr(e, 'response') and e.response and e.response.text:
            print(f"   Response: {e.response.text}")
        return False


def get_collections_schema():
    """Return all collection schemas."""
    return [
        {
            "name": "settings",
            "type": "base",
            "fields": [  # Changed from 'schema' to 'fields'
                {"name": "user", "type": "relation", "required": True, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "pageTapEnabled", "type": "bool", "required": False},
                {"name": "pageSwipeEnabled", "type": "bool", "required": False},
                {"name": "contrastMode", "type": "number", "required": False, "options": {"min": 0, "max": 3}},
                {"name": "convertToTraditionalChinese", "type": "bool", "required": False},
                {"name": "serverBaseUrl", "type": "text", "required": False},
                {"name": "exportToCustomUrl", "type": "bool", "required": False},
                {"name": "exportCustomUrl", "type": "text", "required": False},
                {"name": "exportToLocalDownloads", "type": "bool", "required": False},
                {"name": "apiKey", "type": "text", "required": False},
                {"name": "aiModelName", "type": "text", "required": False},
                {"name": "aiSystemPrompt", "type": "text", "required": False},
                {"name": "aiUserPromptTemplate", "type": "text", "required": False},
                {"name": "temperature", "type": "number", "required": False},
                {"name": "maxTokens", "type": "number", "required": False},
                {"name": "topP", "type": "number", "required": False},
                {"name": "frequencyPenalty", "type": "number", "required": False},
                {"name": "presencePenalty", "type": "number", "required": False},
                {"name": "assistantRole", "type": "text", "required": False},
                {"name": "enableGoogleSearch", "type": "bool", "required": False},
                {"name": "useStreaming", "type": "bool", "required": False},
                {"name": "pageAnimationEnabled", "type": "bool", "required": False},
                {"name": "showPageIndicator", "type": "bool", "required": False},
                {"name": "language", "type": "text", "required": False},
                {"name": "activeProfileId", "type": "number", "required": False},
                {"name": "updatedAt", "type": "number", "required": False}
            ],
            "indexes": ["CREATE UNIQUE INDEX idx_settings_user ON settings (user)"],
            "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
        },
        {
            "name": "progress",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": True, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "bookId", "type": "text", "required": True},
                {"name": "bookTitle", "type": "text", "required": False},
                {"name": "locatorJson", "type": "text", "required": True},
                {"name": "updatedAt", "type": "number", "required": False}
            ],
            "indexes": ["CREATE UNIQUE INDEX idx_progress_user_book ON progress (user, bookId)"],
            "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
        },
        {
            "name": "bookmarks",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": True, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "bookId", "type": "text", "required": True},
                {"name": "locatorJson", "type": "text", "required": True},
                {"name": "createdAt", "type": "number", "required": False},
                {"name": "updatedAt", "type": "number", "required": False}
            ],
            "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
        },
        {
            "name": "ai_notes",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": True, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "bookId", "type": "text", "required": False},
                {"name": "bookTitle", "type": "text", "required": False},
                {"name": "messages", "type": "text", "required": True},
                {"name": "originalText", "type": "text", "required": False},
                {"name": "aiResponse", "type": "text", "required": False},
                {"name": "locatorJson", "type": "text", "required": False},
                {"name": "createdAt", "type": "number", "required": False},
                {"name": "updatedAt", "type": "number", "required": False}
            ],
            "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
        },
        {
            "name": "ai_profiles",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": True, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "name", "type": "text", "required": True},
                {"name": "modelName", "type": "text", "required": True},
                {"name": "apiKey", "type": "text", "required": True},
                {"name": "serverBaseUrl", "type": "text", "required": True},
                {"name": "systemPrompt", "type": "text", "required": False},
                {"name": "userPromptTemplate", "type": "text", "required": False},
                {"name": "useStreaming", "type": "bool", "required": False},
                {"name": "temperature", "type": "number", "required": False},
                {"name": "maxTokens", "type": "number", "required": False},
                {"name": "topP", "type": "number", "required": False},
                {"name": "frequencyPenalty", "type": "number", "required": False},
                {"name": "presencePenalty", "type": "number", "required": False},
                {"name": "assistantRole", "type": "text", "required": False},
                {"name": "enableGoogleSearch", "type": "bool", "required": False},
                {"name": "extraParamsJson", "type": "text", "required": False},
                {"name": "createdAt", "type": "number", "required": False},
                {"name": "updatedAt", "type": "number", "required": False}
            ],
            "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
        },
        {
            "name": "books",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": True, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "bookId", "type": "text", "required": True},
                {"name": "title", "type": "text", "required": False},
                {"name": "storagePath", "type": "text", "required": False},
                {"name": "fileHash", "type": "text", "required": False},
                {"name": "deleted", "type": "bool", "required": False},
                {"name": "deletedAt", "type": "number", "required": False},
                {"name": "updatedAt", "type": "number", "required": False}
            ],
            "indexes": ["CREATE UNIQUE INDEX idx_books_user_book ON books (user, bookId)"],
            "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
        },
        {
            "name": "crash_reports",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": False, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "appVersion", "type": "text", "required": True},
                {"name": "androidVersion", "type": "text", "required": False},
                {"name": "deviceModel", "type": "text", "required": False},
                {"name": "stackTrace", "type": "text", "required": True},
                {"name": "message", "type": "text", "required": False},
                {"name": "timestamp", "type": "number", "required": True}
            ],
            "listRule": None,
            "viewRule": None,
            "createRule": "@request.auth.id != \"\"",
            "updateRule": None,
            "deleteRule": None
        }
    ]


def main():
    parser = argparse.ArgumentParser(
        description="Setup PocketBase collections for BooxReader app"
    )
    parser.add_argument(
        "--url",
        required=True,
        help="PocketBase server URL (e.g., http://localhost:8090)"
    )
    parser.add_argument(
        "--email",
        required=True,
        help="Admin email"
    )
    parser.add_argument(
        "--password",
        required=True,
        help="Admin password"
    )
    parser.add_argument(
        "--no-verify-ssl",
        action="store_true",
        help="Disable SSL certificate verification (use for self-signed certs)"
    )
    
    args = parser.parse_args()
    
    base_url = args.url.rstrip("/")
    verify_ssl = not args.no_verify_ssl
    
    if not verify_ssl:
        import urllib3
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        print("⚠️  SSL verification disabled\n")
    
    print("🚀 Starting PocketBase collection setup...")
    print(f"   URL: {base_url}")
    print(f"   Admin: {args.email}\n")
    
    # Authenticate
    print("🔐 Authenticating as admin...")
    token = authenticate_admin(base_url, args.email, args.password, verify_ssl)
    print("✅ Authentication successful\n")
    
    # Create collections
    print("📦 Creating collections...")
    collections = get_collections_schema()
    
    created_count = 0
    for collection_data in collections:
        if create_collection(base_url, token, collection_data):
            created_count += 1
    
    print(f"\n✨ Setup complete!")
    print(f"   Created: {created_count} new collections")
    print(f"   Skipped: {len(collections) - created_count} existing collections")
    print(f"\n💡 Next steps:")
    print(f"   1. Verify collections in admin UI: {base_url}/_/")
    print(f"   2. Update your .env with: POCKETBASE_URL={base_url}")
    print(f"   3. Build and test the app")


if __name__ == "__main__":
    main()
