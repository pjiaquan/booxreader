#!/usr/bin/env python3
"""Check what collections exist and their schemas."""

import requests
import json

import argparse
import sys

def main():
    parser = argparse.ArgumentParser(description='Check PocketBase collections and schema fields.')
    parser.add_argument('--url', default='https://pocket.risc-v.tw', help='PocketBase URL')
    parser.add_argument('--email', required=True, help='Admin email')
    parser.add_argument('--password', required=True, help='Admin password')
    
    args = parser.parse_args()
    
    BASE_URL = args.url
    EMAIL = args.email
    PASSWORD = args.password

    # Authenticate
    auth_url = f"{BASE_URL}/api/collections/_superusers/auth-with-password"
    try:
        response = requests.post(auth_url, json={"identity": EMAIL, "password": PASSWORD})
        response.raise_for_status()
        token = response.json().get("token")
    except requests.exceptions.RequestException as e:
        print(f"❌ Authentication failed: {e}")
        sys.exit(1)

    print(f"✅ Authenticated\n")

    # Get all collections
    collections_url = f"{BASE_URL}/api/collections"
    headers = {"Authorization": f"Bearer {token}"}
    response = requests.get(collections_url, headers=headers)
    data = response.json()

    # Response might be a dict with 'items' key or a list
    if isinstance(data, dict):
        collections = data.get('items', [])
    else:
        collections = data

    # Filter for our collections
    our_collections = ["settings", "progress", "bookmarks", "ai_notes", "ai_profiles", "books", "crash_reports"]

    for collection in collections:
        name = collection.get("name")
        if name in our_collections:
            print(f"📦 Collection: {name}")
            print(f"   ID: {collection.get('id')}")
            print(f"   Type: {collection.get('type')}")
            
            # API returns 'fields' not 'schema'
            fields = collection.get('fields', [])
            print(f"   Fields: {len(fields)}")
            
            # Print field names
            if fields:
                print(f"   Field list:")
                for field in fields:
                    field_type = field.get('type')
                    field_name = field.get('name')
                    required = "✓" if field.get('required') else "✗"
                    print(f"      - {field_name} ({field_type}) [required: {required}]")
            else:
                print(f"   ⚠️  WARNING: No fields found!")
            
            print()

if __name__ == "__main__":
    main()
