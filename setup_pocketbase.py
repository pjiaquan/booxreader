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
import json
import os
import sys
from pathlib import Path

import requests

DEFAULT_SCHEMA_FILE = "pocketbase_collections.json"


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


def build_collection_map(base_url, token):
    """Build a mapping of collection names and IDs to their live PocketBase IDs."""
    collections_url = f"{base_url}/api/collections"
    headers = {"Authorization": f"Bearer {token}"}
    try:
        res = requests.get(collections_url, headers=headers)
        res.raise_for_status()
        data = res.json()
        items = data.get("items", data) if isinstance(data, dict) else data
        c_map = {}
        for c in items:
            cid = c.get("id")
            cname = c.get("name")
            if cid:
                c_map[cid] = cid
            if cname and cid:
                c_map[cname] = cid
        if "users" in c_map:
            c_map["_pb_users_auth_"] = c_map["users"]
        return c_map
    except Exception:
        return {}


def create_collection(base_url, token, collection_data, collection_map=None, include_relations=True):
    """Create or update a single collection with fields, rules, and indexes."""
    collections_url = f"{base_url}/api/collections"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    if collection_map is None:
        collection_map = build_collection_map(base_url, token)

    collection_name = collection_data.get("name")
    if collection_name.startswith("_") or collection_name == "users":
        if include_relations:
            print(f"   Skipping system/built-in collection '{collection_name}'")
        return True

    all_fields = collection_data.get("fields") or collection_data.get("schema") or []

    def clean_field(field):
        f = field.copy()
        if f.get("type") == "file":
            opts = (f.get("options") or {}).copy()
            try:
                ms = int(opts.get("maxSize", 0) or 0)
            except (TypeError, ValueError):
                ms = 0
            if ms <= 0:
                opts["maxSize"] = 5242880
            if not opts.get("maxSelect"):
                opts["maxSelect"] = 1
            f["options"] = opts
        elif f.get("type") == "select":
            opts = (f.get("options") or {}).copy()
            if not opts.get("values"):
                opts["values"] = ["default"]
            if not opts.get("maxSelect"):
                opts["maxSelect"] = 1
            f["options"] = opts
        elif f.get("type") == "relation":
            opts = (f.get("options") or {}).copy()
            rel_target = opts.get("collectionId")
            if not rel_target or rel_target in ["_pb_users_auth_", "users"]:
                opts["collectionId"] = collection_map.get("users", "_pb_users_auth_")
            elif rel_target in collection_map:
                opts["collectionId"] = collection_map[rel_target]
            f["options"] = opts
        return f

    cleaned_fields = []
    for field in all_fields:
        if field.get("name") in ["id", "created", "updated"] or field.get("system"):
            continue
        if not include_relations and field.get("type") == "relation":
            continue
        cleaned_fields.append(clean_field(field))

    collection_payload = {
        "name": collection_name,
        "type": collection_data.get("type", "base"),
        "schema": cleaned_fields,
        "listRule": collection_data.get("listRule") if include_relations else None,
        "viewRule": collection_data.get("viewRule") if include_relations else None,
        "createRule": collection_data.get("createRule") if include_relations else None,
        "updateRule": collection_data.get("updateRule") if include_relations else None,
        "deleteRule": collection_data.get("deleteRule") if include_relations else None,
        "indexes": collection_data.get("indexes", []) if include_relations else []
    }

    def get_existing_collection():
        response = requests.get(collections_url, headers=headers)
        response.raise_for_status()
        data = response.json()
        collections = data.get("items", data) if isinstance(data, dict) else data
        for c in collections:
            if c.get("name") == collection_name:
                return c
        return None

    try:
        response = requests.post(collections_url, headers=headers, json=collection_payload)

        if response.status_code == 400:
            error_data = response.json() if response.text else {}
            if "already exists" in str(error_data).lower() or "name_exists" in str(error_data):
                existing = get_existing_collection()
                if existing:
                    collection_id = existing.get("id")
                    update_url = f"{collections_url}/{collection_id}"
                    update_resp = requests.patch(update_url, headers=headers, json=collection_payload)
                    if update_resp.status_code == 200:
                        if include_relations:
                            print(f"⚠️  Updated collection: {collection_name}")
                        return True
                    else:
                        if include_relations:
                            print(f"⚠️  Collection '{collection_name}' exists, patch response: {update_resp.status_code} {update_resp.text}")
                        return True
            else:
                if include_relations:
                    print(f"   Error response: {response.text}")
                return False

        response.raise_for_status()
        if include_relations:
            print(f"✅ Created collection: {collection_name}")
        return True

    except requests.exceptions.RequestException as e:
        if include_relations:
            print(f"❌ Failed to create/update collection '{collection_name}': {e}")
            if hasattr(e, 'response') and e.response and e.response.text:
                print(f"   Response: {e.response.text}")
        return False


def ensure_embedding_vector_capacity(base_url, token, verify_ssl=True, min_max=200000):
    """Ensure embeddings.vectorJson has enough max length for serialized vectors."""
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    collections_url = f"{base_url}/api/collections"

    try:
        response = requests.get(collections_url, headers=headers, verify=verify_ssl)
        response.raise_for_status()
        payload = response.json()
        items = payload.get("items", payload) if isinstance(payload, dict) else payload
        target = None
        for item in items:
            if item.get("name") == "embeddings":
                target = item
                break
        if not target:
            print("   ℹ️  embeddings collection not found, skip vectorJson max check")
            return

        fields = list(target.get("fields", []))
        changed = False
        for field in fields:
            if field.get("name") != "vectorJson":
                continue
            current_max = field.get("max")
            try:
                current_max_num = int(current_max) if current_max is not None else 0
            except (TypeError, ValueError):
                current_max_num = 0
            if current_max_num < int(min_max):
                field["max"] = int(min_max)
                changed = True
            break

        if not changed:
            print("   ✅ embeddings.vectorJson max is already sufficient")
            return

        patch_url = f"{collections_url}/{target.get('id')}"
        patch_resp = requests.patch(
            patch_url,
            headers=headers,
            json={"fields": fields},
            verify=verify_ssl,
        )
        patch_resp.raise_for_status()
        print(f"   ✅ Updated embeddings.vectorJson max to {int(min_max)}")
    except requests.exceptions.RequestException as e:
        print(f"   ⚠️  Warning: failed to enforce embeddings.vectorJson max: {e}")
        if hasattr(e, "response") and e.response is not None:
            try:
                print(f"      {e.response.text[:300]}")
            except Exception:
                pass


def fetch_remote_schema(base_url, token, verify_ssl=True):
    """Return the PocketBase collections definition from the server."""

    collections_url = f"{base_url}/api/collections"
    headers = {"Authorization": f"Bearer {token}"}
    response = requests.get(collections_url, headers=headers, verify=verify_ssl)
    response.raise_for_status()

    data = response.json()
    items = data.get("items", data) if isinstance(data, dict) else data

    normalized = []
    for entry in items:
        normalized.append({
            "name": entry.get("name"),
            "type": entry.get("type"),
            "schema": entry.get("schema") or entry.get("fields") or [],
            "indexes": entry.get("indexes"),
            "listRule": entry.get("listRule"),
            "viewRule": entry.get("viewRule"),
            "createRule": entry.get("createRule"),
            "updateRule": entry.get("updateRule"),
            "deleteRule": entry.get("deleteRule")
        })
    return normalized


def save_schema_to_file(collections, file_path):
    """Persist a schema snapshot to disk."""

    dest = Path(file_path)
    dest.parent.mkdir(parents=True, exist_ok=True)
    payload = {"collections": collections}
    with dest.open("w", encoding="utf-8") as schema_file:
        json.dump(payload, schema_file, ensure_ascii=False, indent=2)


def load_schema_from_file(path):
    """Load a schema file and normalize it for apply/create logic."""

    with open(path, "r", encoding="utf-8") as schema_file:
        content = json.load(schema_file)

    if isinstance(content, dict) and "collections" in content:
        collections = content["collections"]
    elif isinstance(content, list):
        collections = content
    elif isinstance(content, dict):
        collections = [content]
    else:
        raise ValueError("Schema file format is not supported")

    normalized = []
    for entry in collections:
        fields = entry.get("fields") or entry.get("schema") or []
        if not isinstance(fields, list):
            fields = []
        normalized.append({
            "name": entry.get("name"),
            "type": entry.get("type", entry.get("collectionType", "base")),
            "fields": fields,
            "indexes": entry.get("indexes"),
            "listRule": entry.get("listRule"),
            "viewRule": entry.get("viewRule"),
            "createRule": entry.get("createRule"),
            "updateRule": entry.get("updateRule"),
            "deleteRule": entry.get("deleteRule")
        })
    return normalized


def ensure_required_collections(collections, required_collections):
    """Ensure required collections are present by name."""

    merged = list(collections or [])
    existing_names = {
        str(entry.get("name") or "").strip()
        for entry in merged
        if isinstance(entry, dict)
    }

    added = 0
    for required in required_collections or []:
        name = str(required.get("name") or "").strip()
        if not name or name in existing_names:
            continue
        merged.append(required)
        existing_names.add(name)
        added += 1
    return merged, added


def get_default_collections_schema():
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
                {
                    "name": "bookFile",
                    "type": "file",
                    "required": False,
                    "options": {
                        "maxSelect": 1,
                        "maxSize": 104857600,
                        "mimeTypes": ["application/epub+zip"],
                        "thumbs": [],
                        "protected": False
                    }
                },
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
        },
        {
            "name": "qdrant_sync_logs",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": False, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "action", "type": "text", "required": True},
                {"name": "status", "type": "text", "required": True},
                {"name": "recordId", "type": "text", "required": True},
                {"name": "bookId", "type": "text", "required": False},
                {"name": "qdrantCollection", "type": "text", "required": False},
                {"name": "pointId", "type": "text", "required": False},
                {"name": "reason", "type": "text", "required": False},
                {"name": "detail", "type": "text", "required": False},
                {"name": "error", "type": "text", "required": False},
                {"name": "timestamp", "type": "number", "required": True}
            ],
            "listRule": None,
            "viewRule": None,
            "createRule": None,
            "updateRule": None,
            "deleteRule": None
        },
        {
            "name": "documents",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": True, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "documentId", "type": "text", "required": True},
                {"name": "title", "type": "text", "required": False},
                {"name": "source", "type": "text", "required": False},
                {"name": "metadataJson", "type": "text", "required": False},
                {"name": "updatedAt", "type": "number", "required": False}
            ],
            "indexes": ["CREATE UNIQUE INDEX idx_documents_user_document ON documents (user, documentId)"],
            "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
        },
        {
            "name": "chunks",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": True, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "document", "type": "relation", "required": True, "options": {"collectionId": "documents", "cascadeDelete": True, "maxSelect": 1}},
                {"name": "chunkId", "type": "text", "required": True},
                {"name": "chunkIndex", "type": "number", "required": False},
                {"name": "content", "type": "text", "required": True},
                {"name": "metadataJson", "type": "text", "required": False},
                {"name": "updatedAt", "type": "number", "required": False}
            ],
            "indexes": [
                "CREATE UNIQUE INDEX idx_chunks_user_chunk ON chunks (user, chunkId)",
                "CREATE INDEX idx_chunks_document_chunkindex ON chunks (document, chunkIndex)"
            ],
            "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
        },
        {
            "name": "embeddings",
            "type": "base",
            "fields": [
                {"name": "user", "type": "relation", "required": True, "options": {"collectionId": "_pb_users_auth_", "cascadeDelete": False, "maxSelect": 1}},
                {"name": "document", "type": "relation", "required": True, "options": {"collectionId": "documents", "cascadeDelete": True, "maxSelect": 1}},
                {"name": "chunk", "type": "relation", "required": True, "options": {"collectionId": "chunks", "cascadeDelete": True, "maxSelect": 1}},
                {"name": "chunkId", "type": "text", "required": True},
                {"name": "model", "type": "text", "required": False},
                {"name": "dimensions", "type": "number", "required": True},
                {"name": "vectorJson", "type": "text", "required": True, "max": 200000, "min": 0},
                {"name": "norm", "type": "number", "required": False},
                {"name": "metadataJson", "type": "text", "required": False},
                {"name": "updatedAt", "type": "number", "required": False}
            ],
            "indexes": [
                "CREATE UNIQUE INDEX idx_embeddings_user_chunk ON embeddings (user, chunkId)",
                "CREATE INDEX idx_embeddings_document ON embeddings (document)"
            ],
            "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
            "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
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
    parser.add_argument(
        "--schema-file",
        default=DEFAULT_SCHEMA_FILE,
        help="Local schema JSON used to create/update PocketBase (default: pocketbase_collections.json)"
    )
    parser.add_argument(
        "--pull-schema",
        nargs="?",
        const=DEFAULT_SCHEMA_FILE,
        metavar="FILE",
        help="Fetch the existing PocketBase schema and save it to FILE (default: pocketbase_collections.json) without applying changes"
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
    
    if args.pull_schema:
        destination = args.pull_schema or DEFAULT_SCHEMA_FILE
        print("🧭 Pulling schema from server...")
        remote_schema = fetch_remote_schema(base_url, token, verify_ssl)
        save_schema_to_file(remote_schema, destination)
        print(f"✅ Schema snapshot saved to {destination}")
        print(f"   Re-run without --pull-schema (and optionally --schema-file {destination}) to apply these changes.")
        return

    # Create collections
    print("📦 Creating collections...")
    default_collections = get_default_collections_schema()
    schema_path = Path(args.schema_file)
    if schema_path.exists():
        print(f"📄 Loading schema from {schema_path}")
        try:
            collections = load_schema_from_file(schema_path)
        except (ValueError, json.JSONDecodeError) as e:
            print(f"⚠️  Failed to load schema file: {e}")
            print("   Falling back to bundled schema definitions.")
            collections = default_collections
    else:
        print(f"⚠️  Schema file {schema_path} not found, using bundled defaults.")
        collections = default_collections

    if not collections:
        print("⚠️  No collections defined in schema file, using bundled defaults.")
        collections = default_collections

    collections, auto_added = ensure_required_collections(collections, default_collections)
    if auto_added > 0:
        print(f"   ✅ Added {auto_added} missing required collection(s) from bundled defaults")
    
    print("📦 Creating base collections (Pass 1)...")
    collection_map = build_collection_map(base_url, token)
    for collection_data in collections:
        create_collection(base_url, token, collection_data, collection_map, include_relations=False)

    print("\n📦 Updating relation fields, rules & indexes (Pass 2)...")
    collection_map = build_collection_map(base_url, token)
    created_count = 0
    for collection_data in collections:
        if create_collection(base_url, token, collection_data, collection_map, include_relations=True):
            created_count += 1

    ensure_embedding_vector_capacity(base_url, token, verify_ssl=verify_ssl, min_max=200000)
    
    print(f"\n✨ Setup complete!")
    print(f"   Created: {created_count} new collections")
    print(f"   Skipped: {len(collections) - created_count} existing collections")
    print(f"\n💡 Next steps:")
    print(f"   1. Verify collections in admin UI: {base_url}/_/")
    print(f"   2. Update your .env with: POCKETBASE_URL={base_url}")
    print(f"   3. Build and test the app")


if __name__ == "__main__":
    main()
