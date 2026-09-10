#!/usr/bin/env python3
"""
只建立 BooxReader 需要的 `annotations` collection（畫線 / 註記同步用）。

為什麼不直接用 setup_pocketbase.py：
  setup_pocketbase.py 會對「同名且已存在」的 collection 發 PATCH，把欄位與規則
  覆蓋成 schema 檔的內容；而它的預設 schema 檔 pocketbase_collections.json 是舊版
  快照。對正式站執行有可能改動既有 collection 的規則。

這支腳本只做一件事，而且是幂等的：
  - annotations 已存在 -> 什麼都不做（不 PATCH、不修改任何欄位或規則）
  - annotations 不存在 -> 只建立它

用法：
  python3 scripts/add_annotations_collection.py \
      --url https://pocket.risc-v.tw \
      --email admin@example.com \
      --password '***'

  加上 --dry-run 只檢查現況，不會建立任何東西。

密碼不會經過命令列（命令列的參數會進入 shell history 與 process list）：
  1. 優先讀環境變數 POCKETBASE_ADMIN_PASSWORD
  2. 否則互動式提示輸入（不回顯）
只有在無法提示時（非互動環境）才需要 --password，且會印出警告。
"""

import argparse
import getpass
import os
import sys
from typing import Optional

import requests

COLLECTION_NAME = "annotations"


def authenticate(base_url: str, email: str, password: str, verify_ssl: bool) -> Optional[str]:
    """以 superuser 身分登入；回傳 token 或 None。"""
    attempts = [
        (f"{base_url}/api/collections/_superusers/auth-with-password", "identity"),
        (f"{base_url}/api/collections/_superusers/auth-with-password", "email"),
        (f"{base_url}/api/admins/auth-with-password", "email"),
        (f"{base_url}/api/admins/auth-with-password", "identity"),
    ]
    for url, field in attempts:
        try:
            response = requests.post(
                url,
                json={field: email, "password": password},
                verify=verify_ssl,
                timeout=20,
            )
        except requests.exceptions.RequestException as exc:
            print(f"   ! {url} -> {exc}")
            continue
        if response.status_code == 200:
            token = response.json().get("token")
            if token:
                print(f"   ✓ 登入成功（{url} , {field}）")
                return token
        elif response.status_code not in (404, 400):
            print(f"   ! {url} -> HTTP {response.status_code} {response.text[:200]}")
    return None


def list_collections(base_url: str, token: str, verify_ssl: bool) -> list:
    response = requests.get(
        f"{base_url}/api/collections?perPage=200",
        headers={"Authorization": f"Bearer {token}"},
        verify=verify_ssl,
        timeout=20,
    )
    response.raise_for_status()
    data = response.json()
    # PocketBase 0.23+ 回傳 {items: [...]}，舊版直接回傳 list
    return data.get("items", data) if isinstance(data, dict) else data


def build_payload(users_collection_id: str) -> dict:
    rules = '@request.auth.id != "" && user = @request.auth.id'
    return {
        "name": COLLECTION_NAME,
        "type": "base",
        "fields": [
            {
                "name": "user",
                "type": "relation",
                "required": True,
                "options": {
                    "collectionId": users_collection_id,
                    "cascadeDelete": False,
                    "maxSelect": 1,
                },
            },
            {"name": "bookId", "type": "text", "required": True},
            {"name": "locatorJson", "type": "text", "required": True},
            {"name": "selectedText", "type": "text", "required": False},
            {"name": "note", "type": "text", "required": False},
            {"name": "style", "type": "text", "required": False},
            {"name": "createdAt", "type": "number", "required": False},
            {"name": "updatedAt", "type": "number", "required": False},
        ],
        "listRule": rules,
        "viewRule": rules,
        "createRule": rules,
        "updateRule": rules,
        "deleteRule": rules,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", required=True, help="PocketBase URL（例如 https://pocket.risc-v.tw）")
    parser.add_argument("--email", required=True, help="superuser email")
    parser.add_argument(
        "--password",
        required=False,
        default=None,
        help="superuser 密碼（建議改用 POCKETBASE_ADMIN_PASSWORD 或互動輸入，避免留在 shell history）",
    )
    parser.add_argument("--no-verify-ssl", action="store_true", help="停用 SSL 憑證驗證")
    parser.add_argument("--dry-run", action="store_true", help="只檢查現況，不建立任何東西")
    args = parser.parse_args()

    base_url = args.url.rstrip("/")
    verify_ssl = not args.no_verify_ssl

    password = args.password or os.environ.get("POCKETBASE_ADMIN_PASSWORD")
    if password is None:
        if sys.stdin.isatty():
            password = getpass.getpass("PocketBase superuser 密碼（不會顯示）: ")
        else:
            parser.error(
                "找不到密碼：請設定 POCKETBASE_ADMIN_PASSWORD 環境變數，或在互動式終端執行"
            )
    elif args.password is not None:
        print(
            "⚠️  偵測到 --password：命令列參數會留在 shell history 與 process list。\n"
            "   建議改用 `read -s POCKETBASE_ADMIN_PASSWORD && export POCKETBASE_ADMIN_PASSWORD`。\n"
        )

    print(f"PocketBase: {base_url}")
    print("1) 登入…")
    token = authenticate(base_url, args.email, password, verify_ssl)
    if not token:
        print("✗ 登入失敗：請確認 email / password 是 PocketBase 的 superuser 帳號")
        print("   （PocketBase 0.23+ 可用 ./pocketbase superuser upsert <email> <password> 重設）")
        return 1

    print("2) 讀取現有 collections…")
    collections = list_collections(base_url, token, verify_ssl)
    names = [c.get("name") for c in collections]
    print(f"   目前共 {len(names)} 個：{', '.join(sorted(n for n in names if n))}")

    if COLLECTION_NAME in names:
        print(f"\n✓ '{COLLECTION_NAME}' 已存在，無需任何變更（本腳本不會修改既有 collection）")
        return 0

    users = next((c for c in collections if c.get("name") == "users"), None)
    users_id = (users or {}).get("id") or "_pb_users_auth_"
    print(f"   users collection id = {users_id}")

    if args.dry_run:
        print(f"\n(dry-run) 會建立 '{COLLECTION_NAME}'：")
        payload = build_payload(users_id)
        print(f"   欄位：{', '.join(f['name'] for f in payload['fields'])}")
        print(f"   規則：{payload['listRule']}")
        return 0

    print(f"3) 建立 '{COLLECTION_NAME}'…")
    response = requests.post(
        f"{base_url}/api/collections",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        json=build_payload(users_id),
        verify=verify_ssl,
        timeout=20,
    )
    if response.status_code in (200, 201):
        print(f"✓ 已建立 '{COLLECTION_NAME}'")
        print("  之後開啟 App 執行一次完整同步，本機既有的畫線就會補推上去。")
        return 0

    print(f"✗ 建立失敗：HTTP {response.status_code}")
    print(f"  {response.text[:500]}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
