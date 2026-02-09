#!/usr/bin/env python3
"""
Backfill existing PocketBase ai_notes into Qdrant with Alibaba embeddings.

What it does:
1) Authenticates to PocketBase (prefers admin credentials)
2) Paginates through ai_notes (optionally only status=done)
3) Builds embedding text from originalText + aiResponse
4) Calls Alibaba DashScope embedding API (OpenAI-compatible endpoint)
5) Upserts points into Qdrant in batches

Usage examples:
  python3 scripts/backfill_ai_notes_to_qdrant.py --dry-run
  python3 scripts/backfill_ai_notes_to_qdrant.py --limit 500 --batch-size 64
  python3 scripts/backfill_ai_notes_to_qdrant.py --only-done true

Optional env/.env keys:
  POCKETBASE_URL
  POCKETBASE_ADMIN_EMAIL
  POCKETBASE_ADMIN_PASSWORD
  DASHSCOPE_API_KEY
  QDRANT_URL
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from urllib.parse import quote

import requests


DEFAULT_EMBEDDING_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/embeddings"
DEFAULT_MODEL = "text-embedding-v4"
DEFAULT_DIMENSIONS = 1024
DEFAULT_COLLECTION = "ai_notes"


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


def point_id_from_pb_id(pb_id: str) -> str:
    digest = hashlib.md5(pb_id.encode("utf-8")).hexdigest()
    return f"{digest[0:8]}-{digest[8:12]}-{digest[12:16]}-{digest[16:20]}-{digest[20:32]}"


def compact_text(text: str, limit: int) -> str:
    normalized = " ".join((text or "").split())
    return normalized[:limit]


def build_text_to_embed(record: Dict[str, object], max_chars: int) -> str:
    original = str(record.get("originalText") or "")
    answer = str(record.get("aiResponse") or "")
    merged = f"Title: {original}\n\nContent: {answer}"
    if len(merged) > max_chars:
        merged = merged[:max_chars]
    return merged


def pb_headers(token: str) -> Dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def try_admin_auth(base_url: str, email: str, password: str, verify_ssl: bool) -> Optional[str]:
    candidates = (
        "/api/collections/_superusers/auth-with-password",
        "/api/admins/auth-with-password",
    )
    payload = {"identity": email, "password": password}
    for path in candidates:
        url = f"{base_url}{path}"
        try:
            resp = requests.post(url, json=payload, timeout=30, verify=verify_ssl)
        except requests.RequestException:
            continue
        if resp.status_code == 200:
            body = resp.json() if resp.text else {}
            token = (body.get("token") or "").strip()
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
            token = (body.get("token") or "").strip()
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
    filters: List[str] = []
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


def fetch_embedding(
    api_key: str,
    embedding_url: str,
    model: str,
    dimensions: int,
    text: str,
    verify_ssl: bool,
) -> List[float]:
    resp = requests.post(
        embedding_url,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json={
            "model": model,
            "input": text,
            "dimensions": dimensions,
            "encoding_format": "float",
        },
        timeout=120,
        verify=verify_ssl,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"embedding API failed: {resp.status_code} {resp.text[:500]}")
    body = resp.json() if resp.text else {}
    vector = ((body.get("data") or [{}])[0] or {}).get("embedding")
    if not isinstance(vector, list):
        raise RuntimeError("embedding missing")
    if len(vector) != dimensions:
        raise RuntimeError(f"embedding dim mismatch: {len(vector)} != {dimensions}")
    if any((not isinstance(v, (int, float)) or not float(v) == float(v) or float(v) in (float("inf"), float("-inf"))) for v in vector):
        raise RuntimeError("embedding contains non-finite values")
    return [float(v) for v in vector]


def upsert_points(
    qdrant_url: str,
    collection: str,
    points: List[Dict[str, object]],
    verify_ssl: bool,
) -> None:
    if not points:
        return
    endpoint = f"{qdrant_url}/collections/{quote(collection)}/points?wait=true"
    resp = requests.put(
        endpoint,
        headers={"Content-Type": "application/json"},
        data=json.dumps({"points": points}),
        timeout=60,
        verify=verify_ssl,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"qdrant upsert failed: {resp.status_code} {resp.text[:500]}")


@dataclass
class Counters:
    seen: int = 0
    skipped_short_answer: int = 0
    skipped_empty_text: int = 0
    embedded: int = 0
    upserted: int = 0
    failed: int = 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Backfill PocketBase ai_notes embeddings into Qdrant."
    )
    parser.add_argument("--url", help="PocketBase base URL, e.g. https://pb.example.com")
    parser.add_argument("--admin-email", help="PocketBase admin/superuser email")
    parser.add_argument("--admin-password", help="PocketBase admin/superuser password")
    parser.add_argument("--email", help="PocketBase users collection email (fallback auth)")
    parser.add_argument("--password", help="PocketBase users collection password (fallback auth)")
    parser.add_argument("--dashscope-api-key", help="Alibaba DashScope API key")
    parser.add_argument("--embedding-url", default=DEFAULT_EMBEDDING_URL, help="Embedding API URL")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="Embedding model")
    parser.add_argument("--dimensions", type=int, default=DEFAULT_DIMENSIONS, help="Embedding dimensions")
    parser.add_argument("--qdrant-url", help="Qdrant base URL, e.g. http://127.0.0.1:6333")
    parser.add_argument("--collection", default=DEFAULT_COLLECTION, help="Qdrant collection")
    parser.add_argument("--per-page", type=int, default=100, help="PocketBase page size")
    parser.add_argument("--batch-size", type=int, default=32, help="Qdrant upsert batch size")
    parser.add_argument("--limit", type=int, default=0, help="Max notes to process, 0 means unlimited")
    parser.add_argument("--max-chars", type=int, default=6000, help="Max chars for embedding input")
    parser.add_argument(
        "--only-done",
        default="false",
        help="Only process status='done' (true/false). Default: false (include all statuses).",
    )
    parser.add_argument("--user-id", help="Process only one user id (optional)")
    parser.add_argument("--dry-run", action="store_true", help="Do not call embedding/Qdrant, only count")
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
    api_key = resolve_value(args.dashscope_api_key, "DASHSCOPE_API_KEY", file_env)
    qdrant_url = resolve_value(args.qdrant_url, "QDRANT_URL", file_env) or "http://127.0.0.1:6333"
    qdrant_url = qdrant_url.rstrip("/")

    verify_ssl = not args.insecure
    only_done = parse_bool(args.only_done)
    dimensions = args.dimensions
    if dimensions <= 0:
        print("Error: --dimensions must be > 0", file=sys.stderr)
        return 2
    if args.batch_size <= 0:
        print("Error: --batch-size must be > 0", file=sys.stderr)
        return 2
    if args.per_page <= 0:
        print("Error: --per-page must be > 0", file=sys.stderr)
        return 2

    # Auth
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

    if not args.dry_run and not api_key:
        print("Error: missing DashScope API key. Use --dashscope-api-key or DASHSCOPE_API_KEY.", file=sys.stderr)
        return 2

    print(
        f"Start backfill: pb={base_url}, qdrant={qdrant_url}, collection={args.collection}, "
        f"auth={auth_mode}, only_done={only_done}, user_filter={filter_user_id or '<none>'}, "
        f"dry_run={args.dry_run}"
    )

    counters = Counters()
    page = 1
    total_items_hint = None
    upsert_batch: List[Dict[str, object]] = []
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
                if args.limit > 0 and counters.seen > args.limit:
                    break

                pb_id = str(record.get("id") or "").strip()
                if not pb_id:
                    counters.failed += 1
                    continue

                ai_response = str(record.get("aiResponse") or "")
                if len(ai_response.strip()) < 2:
                    counters.skipped_short_answer += 1
                    continue

                text_to_embed = build_text_to_embed(record, max_chars=args.max_chars)
                if not text_to_embed.strip():
                    counters.skipped_empty_text += 1
                    continue

                point_payload = {
                    "pb_id": pb_id,
                    "user_id": str(record.get("user") or ""),
                    "book_id": str(record.get("bookId") or ""),
                }

                if args.dry_run:
                    counters.embedded += 1
                    counters.upserted += 1
                    if counters.upserted % 50 == 0:
                        print(f"[dry-run] processed {counters.upserted} notes")
                    continue

                try:
                    vector = fetch_embedding(
                        api_key=api_key or "",
                        embedding_url=args.embedding_url,
                        model=args.model,
                        dimensions=dimensions,
                        text=text_to_embed,
                        verify_ssl=verify_ssl,
                    )
                    counters.embedded += 1
                    upsert_batch.append(
                        {
                            "id": point_id_from_pb_id(pb_id),
                            "vector": vector,
                            "payload": point_payload,
                        }
                    )
                    if len(upsert_batch) >= args.batch_size:
                        upsert_points(
                            qdrant_url=qdrant_url,
                            collection=args.collection,
                            points=upsert_batch,
                            verify_ssl=verify_ssl,
                        )
                        counters.upserted += len(upsert_batch)
                        print(f"upserted {counters.upserted} points")
                        upsert_batch.clear()
                except Exception as exc:  # noqa: BLE001
                    counters.failed += 1
                    print(f"[WARN] pb_id={pb_id} failed: {exc}", file=sys.stderr)

            if args.limit > 0 and counters.seen >= args.limit:
                break
            page += 1

        if upsert_batch and not args.dry_run:
            upsert_points(
                qdrant_url=qdrant_url,
                collection=args.collection,
                points=upsert_batch,
                verify_ssl=verify_ssl,
            )
            counters.upserted += len(upsert_batch)
            print(f"upserted {counters.upserted} points")
            upsert_batch.clear()
    except Exception as exc:  # noqa: BLE001
        print(f"Fatal error: {exc}", file=sys.stderr)
        return 1

    elapsed = time.time() - start_ts
    print("Done.")
    print(f"  seen={counters.seen}")
    print(f"  embedded={counters.embedded}")
    print(f"  upserted={counters.upserted}")
    print(f"  skipped_short_answer={counters.skipped_short_answer}")
    print(f"  skipped_empty_text={counters.skipped_empty_text}")
    print(f"  failed={counters.failed}")
    print(f"  elapsed_sec={elapsed:.1f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
