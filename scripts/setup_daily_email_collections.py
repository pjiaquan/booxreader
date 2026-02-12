#!/usr/bin/env python3
"""
Setup PocketBase collections/fields required for daily summary email.

This script is idempotent:
- Ensures `mail_queue` collection exists (create or patch missing fields/rules/indexes)
- Ensures `settings` collection contains daily email related fields

Usage:
  python3 scripts/setup_daily_email_collections.py \
      --url https://your-pocketbase.example.com \
      --email admin@example.com \
      --password '***'
"""

import argparse
import sys
from typing import Dict, List, Optional, Tuple

import requests


MAIL_QUEUE_NAME = "mail_queue"
SETTINGS_NAME = "settings"


def authenticate_admin(base_url: str, email: str, password: str, verify_ssl: bool = True) -> str:
    """Authenticate as superuser/admin and return token."""
    payloads = [{"email": email, "password": password}, {"identity": email, "password": password}]
    endpoints = [
        f"{base_url}/api/collections/_superusers/auth-with-password",
        f"{base_url}/api/admins/auth-with-password",
    ]

    last_error = None
    for endpoint in endpoints:
        for payload in payloads:
            try:
                response = requests.post(
                    endpoint,
                    json=payload,
                    verify=verify_ssl,
                    headers={"Content-Type": "application/json"},
                    timeout=20,
                )
            except requests.RequestException as exc:
                last_error = str(exc)
                continue

            if response.status_code == 200:
                data = response.json()
                token = data.get("token") or data.get("record", {}).get("token")
                if token:
                    return token
                last_error = f"auth success but token missing: {data}"
                continue

            last_error = f"{response.status_code} {response.text}"

    raise RuntimeError(f"Authentication failed: {last_error}")


def fetch_collections(base_url: str, token: str, verify_ssl: bool = True) -> List[Dict]:
    headers = {"Authorization": f"Bearer {token}"}
    response = requests.get(
        f"{base_url}/api/collections",
        headers=headers,
        verify=verify_ssl,
        timeout=20,
    )
    response.raise_for_status()
    data = response.json()
    return data.get("items", data) if isinstance(data, dict) else data


def find_collection(collections: List[Dict], name: str) -> Optional[Dict]:
    for item in collections:
        if item.get("name") == name:
            return item
    return None


def merge_missing_fields(existing_fields: List[Dict], required_fields: List[Dict]) -> Tuple[List[Dict], int]:
    existing_names = {field.get("name") for field in existing_fields}
    merged = list(existing_fields)
    added = 0
    for field in required_fields:
        if field.get("name") not in existing_names:
            merged.append(field)
            added += 1
    return merged, added


def normalize_fields_for_patch(
    fields: List[Dict],
    default_relation_collection_id: Optional[str] = None,
) -> List[Dict]:
    """
    Normalize fetched field schema so it can be sent back to PATCH /api/collections.

    Some PocketBase versions return relation options under `options.collectionId`
    but expect top-level `collectionId` in collection update payloads.
    """
    normalized: List[Dict] = []
    for field in fields:
        item = dict(field)
        if item.get("type") == "relation":
            options = item.get("options") or {}
            collection_id = (
                item.get("collectionId")
                or options.get("collectionId")
                or default_relation_collection_id
            )
            if collection_id:
                item["collectionId"] = collection_id
            if "cascadeDelete" not in item:
                item["cascadeDelete"] = bool(options.get("cascadeDelete", False))
            if "minSelect" not in item:
                item["minSelect"] = int(options.get("minSelect", 0) or 0)
            if "maxSelect" not in item:
                item["maxSelect"] = int(options.get("maxSelect", 1) or 1)
            item.pop("options", None)
        normalized.append(item)
    return normalized


def patch_collection(
    base_url: str,
    token: str,
    collection_id: str,
    patch_payload: Dict,
    verify_ssl: bool = True,
) -> None:
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    response = requests.patch(
        f"{base_url}/api/collections/{collection_id}",
        headers=headers,
        json=patch_payload,
        verify=verify_ssl,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(
            f"PATCH /api/collections/{collection_id} failed: "
            f"{response.status_code} {response.text}"
        )


def ensure_settings_fields(base_url: str, token: str, verify_ssl: bool = True) -> None:
    collections = fetch_collections(base_url, token, verify_ssl)
    settings = find_collection(collections, SETTINGS_NAME)
    if settings is None:
        print(f"⚠️  '{SETTINGS_NAME}' collection not found, skip patching settings fields.")
        return

    existing_fields = settings.get("fields") or settings.get("schema") or []
    required_fields = [
        {"name": "autoCheckUpdates", "type": "bool", "required": False},
        {"name": "dailySummaryEmailEnabled", "type": "bool", "required": False},
        {
            "name": "dailySummaryEmailHour",
            "type": "number",
            "required": False,
            "options": {"min": 0, "max": 23},
        },
        {
            "name": "dailySummaryEmailMinute",
            "type": "number",
            "required": False,
            "options": {"min": 0, "max": 59},
        },
        {"name": "dailySummaryEmailTo", "type": "text", "required": False},
    ]
    merged_fields, added = merge_missing_fields(existing_fields, required_fields)
    if added == 0:
        print("✅ settings: daily-email fields already present")
        return

    merged_fields = normalize_fields_for_patch(merged_fields)
    patch_collection(
        base_url,
        token,
        settings["id"],
        {"fields": merged_fields},
        verify_ssl,
    )
    print(f"✅ settings: added {added} missing field(s)")


def ensure_mail_queue_collection(base_url: str, token: str, verify_ssl: bool = True) -> None:
    collections = fetch_collections(base_url, token, verify_ssl)
    users_collection = find_collection(collections, "users")
    if users_collection is None:
        raise RuntimeError("PocketBase auth collection 'users' not found.")
    required_fields = [
        {
            "name": "user",
            "type": "relation",
            "required": True,
            "collectionId": users_collection.get("id") or "_pb_users_auth_",
            "cascadeDelete": False,
            "minSelect": 0,
            "maxSelect": 1,
        },
        {"name": "toEmail", "type": "text", "required": True, "min": 0, "max": 0, "pattern": ""},
        {"name": "subject", "type": "text", "required": True, "min": 0, "max": 0, "pattern": ""},
        {"name": "body", "type": "text", "required": True, "min": 0, "max": 0, "pattern": ""},
        {"name": "category", "type": "text", "required": False, "min": 0, "max": 0, "pattern": ""},
        {"name": "status", "type": "text", "required": False, "min": 0, "max": 0, "pattern": ""},
        {"name": "error", "type": "text", "required": False, "min": 0, "max": 0, "pattern": ""},
        {
            "name": "createdAt",
            "type": "number",
            "required": False,
            "min": None,
            "max": None,
            "onlyInt": True,
        },
    ]

    rules_payload = {
        "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
        "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
        "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
        "updateRule": None,
        "deleteRule": None,
        "indexes": [
            "CREATE INDEX idx_mail_queue_user_createdAt ON mail_queue (user, createdAt)"
        ],
    }

    existing = find_collection(collections, MAIL_QUEUE_NAME)
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    if existing is None:
        # Compatibility mode: create bare collection first, then patch fields/rules.
        create_payload = {
            "name": MAIL_QUEUE_NAME,
            "type": "base",
        }
        response = requests.post(
            f"{base_url}/api/collections",
            headers=headers,
            json=create_payload,
            verify=verify_ssl,
            timeout=30,
        )
        if not response.ok:
            raise RuntimeError(
                f"POST /api/collections failed: {response.status_code} {response.text}"
            )
        created = response.json()
        collection_id = created.get("id")
        if not collection_id:
            refreshed = fetch_collections(base_url, token, verify_ssl)
            created_entry = find_collection(refreshed, MAIL_QUEUE_NAME)
            collection_id = created_entry.get("id") if created_entry else None
        if not collection_id:
            raise RuntimeError("mail_queue created but id not found")

        patch_payload = {
            "fields": normalize_fields_for_patch(
                required_fields,
                default_relation_collection_id=users_collection.get("id"),
            ),
            **rules_payload,
        }
        patch_collection(base_url, token, collection_id, patch_payload, verify_ssl)
        print(f"✅ created collection: {MAIL_QUEUE_NAME}")
        return

    existing_fields = existing.get("fields") or existing.get("schema") or []
    merged_fields, added = merge_missing_fields(existing_fields, required_fields)
    patch_payload = {
        "fields": normalize_fields_for_patch(
            merged_fields,
            default_relation_collection_id=users_collection.get("id"),
        ),
        **rules_payload,
    }
    patch_collection(base_url, token, existing["id"], patch_payload, verify_ssl)
    print(
        f"✅ updated collection: {MAIL_QUEUE_NAME}"
        + (f" (added {added} missing field(s))" if added else "")
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Ensure PocketBase mail_queue + daily email settings fields exist."
    )
    parser.add_argument("--url", required=True, help="PocketBase base URL")
    parser.add_argument("--email", required=True, help="PocketBase admin email")
    parser.add_argument("--password", required=True, help="PocketBase admin password")
    parser.add_argument(
        "--no-verify-ssl",
        action="store_true",
        help="Disable SSL certificate verification",
    )
    parser.add_argument(
        "--skip-settings-fields",
        action="store_true",
        help="Skip patching settings collection fields",
    )
    args = parser.parse_args()

    base_url = args.url.rstrip("/")
    verify_ssl = not args.no_verify_ssl
    if not verify_ssl:
        import urllib3

        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

    try:
        print("🔐 authenticating...")
        token = authenticate_admin(base_url, args.email, args.password, verify_ssl)
        print("✅ auth success")

        ensure_mail_queue_collection(base_url, token, verify_ssl)
        if not args.skip_settings_fields:
            ensure_settings_fields(base_url, token, verify_ssl)

        print("✨ done")
    except Exception as exc:
        print(f"❌ {exc}")
        sys.exit(1)


if __name__ == "__main__":
    main()
