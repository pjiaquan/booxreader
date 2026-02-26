#!/usr/bin/env python3
"""
Backfill existing PocketBase ai_notes into documents/chunks/embeddings.

How it works:
1) Authenticate to PocketBase (prefer admin credentials)
2) Page through ai_notes records
3) PATCH each selected record's updatedAt to trigger onRecordUpdate hooks
4) Hook performs RAG upsert into documents/chunks/embeddings

Notes:
- This triggers all ai_notes update hooks (including existing Qdrant sync, if enabled).
- Use --dry-run first to verify candidate counts.

Usage examples:
  python3 scripts/backfill_ai_notes_to_rag_embeddings.py --dry-run
  python3 scripts/backfill_ai_notes_to_rag_embeddings.py --limit 500
  python3 scripts/backfill_ai_notes_to_rag_embeddings.py --only-done true

Optional env/.env keys:
  POCKETBASE_URL
  POCKETBASE_ADMIN_EMAIL
  POCKETBASE_ADMIN_PASSWORD
  POCKETBASE_TEST_EMAIL
  POCKETBASE_TEST_PASSWORD
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional, Tuple

import requests


def load_env_file(path: Path) -> Dict[str, str]:
    env: Dict[str, str] = {}
    if not path.exists():
        return env
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key:
            env[key] = value
    return env


def resolve_value(explicit: Optional[str], env_key: str, file_env: Dict[str, str]) -> Optional[str]:
    if explicit:
        return explicit
    return os.getenv(env_key) or file_env.get(env_key)


def parse_bool(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def pb_headers(token: str) -> Dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }


def try_admin_auth(base_url: str, email: str, password: str, verify_ssl: bool) -> Optional[str]:
    candidates = (
        "/api/collections/_superusers/auth-with-password",
        "/api/admins/auth-with-password",
    )
    payloads = (
        {"identity": email, "password": password},
        {"email": email, "password": password},
    )
    for path in candidates:
        url = f"{base_url}{path}"
        for payload in payloads:
            try:
                resp = requests.post(url, json=payload, timeout=30, verify=verify_ssl)
            except requests.RequestException:
                continue
            if resp.status_code == 200:
                body = resp.json() if resp.text else {}
                token = str(body.get("token") or "").strip()
                if token:
                    return token
    return None


def user_auth(base_url: str, email: str, password: str, verify_ssl: bool) -> Tuple[str, str]:
    endpoint = f"{base_url}/api/collections/users/auth-with-password"
    payloads = (
        {"identity": email, "password": password},
        {"email": email, "password": password},
    )
    last_err = ""
    for payload in payloads:
        resp = requests.post(endpoint, json=payload, timeout=30, verify=verify_ssl)
        if resp.status_code == 200:
            body = resp.json() if resp.text else {}
            token = str(body.get("token") or "").strip()
            user_id = str((body.get("record") or {}).get("id") or "").strip()
            if token and user_id:
                return token, user_id
            last_err = "token or user id missing"
            continue
        last_err = f"{resp.status_code} {resp.text[:400]}"
    raise RuntimeError(f"user auth failed: {last_err}")


def list_ai_notes_page(
    base_url: str,
    token: str,
    page: int,
    per_page: int,
    verify_ssl: bool,
    only_done: bool,
    user_id: Optional[str],
) -> Dict[str, object]:
    endpoint = f"{base_url}/api/collections/ai_notes/records"
    filters = []
    if only_done:
        filters.append("status='done'")
    if user_id:
        filters.append(f"user='{user_id}'")
    params = {"page": page, "perPage": per_page, "sort": "+id"}
    if filters:
        params["filter"] = "(" + "&&".join(filters) + ")"

    resp = requests.get(
        endpoint,
        params=params,
        headers=pb_headers(token),
        timeout=60,
        verify=verify_ssl,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"list ai_notes failed: {resp.status_code} {resp.text[:500]}")
    return resp.json() if resp.text else {}


def touch_ai_note(
    base_url: str,
    token: str,
    record_id: str,
    updated_at_ms: int,
    verify_ssl: bool,
) -> None:
    endpoint = f"{base_url}/api/collections/ai_notes/records/{record_id}"
    payload = {"updatedAt": updated_at_ms}
    resp = requests.patch(
        endpoint,
        json=payload,
        headers=pb_headers(token),
        timeout=60,
        verify=verify_ssl,
    )
    if resp.status_code < 200 or resp.status_code >= 300:
        raise RuntimeError(f"touch failed: {resp.status_code} {resp.text[:500]}")


@dataclass
class Counters:
    seen: int = 0
    selected: int = 0
    touched: int = 0
    skipped_no_response: int = 0
    failed: int = 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Trigger ai_notes update hooks to backfill PocketBase-native RAG embeddings."
    )
    parser.add_argument("--url", help="PocketBase base URL, e.g. https://pb.example.com")
    parser.add_argument("--admin-email", help="PocketBase admin/superuser email")
    parser.add_argument("--admin-password", help="PocketBase admin/superuser password")
    parser.add_argument("--email", help="PocketBase users collection email (fallback auth)")
    parser.add_argument("--password", help="PocketBase users collection password (fallback auth)")
    parser.add_argument("--per-page", type=int, default=100, help="PocketBase page size")
    parser.add_argument("--limit", type=int, default=0, help="Max notes to process, 0 means unlimited")
    parser.add_argument(
        "--only-done",
        default="true",
        help="Only process status='done' (true/false). Default: true.",
    )
    parser.add_argument(
        "--require-ai-response",
        default="true",
        help="Skip notes whose aiResponse is blank (true/false). Default: true.",
    )
    parser.add_argument("--user-id", help="Process only one user id (optional)")
    parser.add_argument("--sleep-ms", type=int, default=0, help="Sleep between updates (throttle)")
    parser.add_argument("--dry-run", action="store_true", help="Do not update records, only count")
    parser.add_argument("--insecure", action="store_true", help="Disable TLS verification")
    args = parser.parse_args()

    file_env = load_env_file(Path(".env"))

    base_url = resolve_value(args.url, "POCKETBASE_URL", file_env)
    if not base_url:
        print("Error: missing PocketBase URL. Use --url or POCKETBASE_URL.", file=sys.stderr)
        return 2
    base_url = base_url.rstrip("/")

    admin_email = resolve_value(args.admin_email, "POCKETBASE_ADMIN_EMAIL", file_env)
    admin_password = resolve_value(args.admin_password, "POCKETBASE_ADMIN_PASSWORD", file_env)
    user_email = resolve_value(args.email, "POCKETBASE_TEST_EMAIL", file_env)
    user_password = resolve_value(args.password, "POCKETBASE_TEST_PASSWORD", file_env)

    verify_ssl = not args.insecure
    only_done = parse_bool(args.only_done)
    require_ai_response = parse_bool(args.require_ai_response)

    if args.per_page <= 0:
        print("Error: --per-page must be > 0", file=sys.stderr)
        return 2
    if args.sleep_ms < 0:
        print("Error: --sleep-ms must be >= 0", file=sys.stderr)
        return 2

    token: Optional[str] = None
    auth_mode = ""
    filter_user_id: Optional[str] = args.user_id
    if admin_email and admin_password:
        token = try_admin_auth(base_url, admin_email, admin_password, verify_ssl=verify_ssl)
        if token:
            auth_mode = "admin"
    if not token and user_email and user_password:
        token, user_id = user_auth(base_url, user_email, user_password, verify_ssl=verify_ssl)
        auth_mode = "user"
        if not filter_user_id:
            filter_user_id = user_id

    if not token:
        print(
            "Error: auth failed. Provide admin creds (--admin-email/--admin-password) "
            "or user creds (--email/--password).",
            file=sys.stderr,
        )
        return 2

    print(
        "Start backfill via ai_notes update hooks: "
        f"pb={base_url}, auth={auth_mode}, only_done={only_done}, "
        f"require_ai_response={require_ai_response}, user_filter={filter_user_id or '<none>'}, "
        f"dry_run={args.dry_run}"
    )

    counters = Counters()
    page = 1
    total_items_hint = None
    start_ts = time.time()

    try:
        while True:
            listing = list_ai_notes_page(
                base_url=base_url,
                token=token,
                page=page,
                per_page=args.per_page,
                verify_ssl=verify_ssl,
                only_done=only_done,
                user_id=filter_user_id,
            )
            if total_items_hint is None:
                total_items_hint = int(listing.get("totalItems") or 0)
                print(f"Total candidate notes (server hint): {total_items_hint}")

            items = listing.get("items") or []
            if not items:
                break

            for record in items:
                counters.seen += 1
                if args.limit > 0 and counters.selected >= args.limit:
                    break

                record_id = str(record.get("id") or "").strip()
                if not record_id:
                    counters.failed += 1
                    continue

                if require_ai_response:
                    ai_response = str(record.get("aiResponse") or "").strip()
                    if not ai_response:
                        counters.skipped_no_response += 1
                        continue

                counters.selected += 1
                if args.dry_run:
                    if counters.selected % 100 == 0:
                        print(f"[dry-run] selected {counters.selected} notes")
                    continue

                try:
                    touch_ai_note(
                        base_url=base_url,
                        token=token,
                        record_id=record_id,
                        updated_at_ms=int(time.time() * 1000),
                        verify_ssl=verify_ssl,
                    )
                    counters.touched += 1
                    if counters.touched % 50 == 0:
                        print(f"touched {counters.touched} notes")
                except Exception as exc:  # noqa: BLE001
                    counters.failed += 1
                    print(f"[WARN] note_id={record_id} failed: {exc}", file=sys.stderr)

                if args.sleep_ms > 0:
                    time.sleep(args.sleep_ms / 1000.0)

            if args.limit > 0 and counters.selected >= args.limit:
                break

            page += 1

    except Exception as exc:  # noqa: BLE001
        print(f"Fatal error: {exc}", file=sys.stderr)
        return 1

    elapsed = time.time() - start_ts
    print("Done.")
    print(f"  seen={counters.seen}")
    print(f"  selected={counters.selected}")
    print(f"  touched={counters.touched}")
    print(f"  skipped_no_response={counters.skipped_no_response}")
    print(f"  failed={counters.failed}")
    print(f"  elapsed_sec={elapsed:.1f}")
    if counters.failed == 0:
        print("Tip: check PocketBase logs for '[RAG Sync Update ...]' to verify hook execution.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
