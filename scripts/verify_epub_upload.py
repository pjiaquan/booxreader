#!/usr/bin/env python3
"""
Verify PocketBase EPUB upload end-to-end.

What this script does:
1) Authenticates with PocketBase user credentials
2) Upserts a `books` record by deterministic bookId (sha256 of file)
3) Uploads EPUB to a file field on books collection
4) Downloads uploaded file back from PocketBase
5) Verifies downloaded bytes hash equals local file hash

Usage:
  python3 scripts/verify_epub_upload.py \
    --email your_user@example.com \
    --password your_password \
    --epub test.epub

Optional env/.env keys:
  POCKETBASE_URL
  POCKETBASE_TEST_EMAIL
  POCKETBASE_TEST_PASSWORD
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path
from typing import Dict, Iterable, Optional, Tuple
from urllib.parse import quote

import requests


FILE_FIELD_CANDIDATES = ("bookFile", "file", "epubFile", "epub", "asset", "book")
MIME_CANDIDATES = ("application/epub+zip", "application/zip", "application/octet-stream")


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
        value = value.strip().strip("'").strip('"')
        if key:
            env[key] = value
    return env


def resolve_value(explicit: Optional[str], env_key: str, file_env: Dict[str, str]) -> Optional[str]:
    if explicit:
        return explicit
    return os.getenv(env_key) or file_env.get(env_key)


def sha256_of_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def auth_user(base_url: str, email: str, password: str, verify_ssl: bool) -> Tuple[str, str]:
    endpoint = f"{base_url}/api/collections/users/auth-with-password"
    payloads = (
        {"identity": email, "password": password},
        {"email": email, "password": password},
    )
    last_error = ""
    for payload in payloads:
        resp = requests.post(endpoint, json=payload, timeout=30, verify=verify_ssl)
        if resp.status_code == 200:
            data = resp.json()
            token = data.get("token")
            user_id = ((data.get("record") or {}).get("id") or "").strip()
            if not token or not user_id:
                raise RuntimeError("Auth succeeded but token/user id missing in response")
            return token, user_id
        last_error = f"{resp.status_code} {resp.text[:400]}"
    raise RuntimeError(f"Authentication failed: {last_error}")


def auth_headers(token: str) -> Dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def query_existing_book(base_url: str, token: str, user_id: str, book_id: str, verify_ssl: bool) -> Optional[dict]:
    endpoint = f"{base_url}/api/collections/books/records"
    filter_value = f"(user='{user_id}'&&bookId='{book_id}')"
    resp = requests.get(
        endpoint,
        params={"filter": filter_value, "perPage": 1},
        headers=auth_headers(token),
        timeout=30,
        verify=verify_ssl,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"Failed to query books: {resp.status_code} {resp.text[:400]}")
    items = (resp.json() or {}).get("items") or []
    return items[0] if items else None


def upsert_book_record(
    base_url: str,
    token: str,
    user_id: str,
    book_id: str,
    title: str,
    verify_ssl: bool,
) -> str:
    existing = query_existing_book(base_url, token, user_id, book_id, verify_ssl=verify_ssl)
    payload = {
        "user": user_id,
        "bookId": book_id,
        "title": title,
        "fileHash": book_id,
        "updatedAt": int(time.time() * 1000),
        "deleted": False,
    }
    if existing:
        record_id = existing.get("id")
        if not record_id:
            raise RuntimeError("Existing book record has no id")
        endpoint = f"{base_url}/api/collections/books/records/{record_id}"
        resp = requests.patch(
            endpoint,
            json=payload,
            headers=auth_headers(token),
            timeout=30,
            verify=verify_ssl,
        )
        if resp.status_code >= 300:
            raise RuntimeError(f"Failed to update books record: {resp.status_code} {resp.text[:400]}")
        return record_id

    endpoint = f"{base_url}/api/collections/books/records"
    resp = requests.post(
        endpoint,
        json=payload,
        headers=auth_headers(token),
        timeout=30,
        verify=verify_ssl,
    )
    if resp.status_code >= 300:
        raise RuntimeError(f"Failed to create books record: {resp.status_code} {resp.text[:400]}")
    data = resp.json() or {}
    record_id = data.get("id")
    if not record_id:
        raise RuntimeError("Create books record succeeded but id missing")
    return record_id


def extract_uploaded_file_name(record: dict, field_name: str) -> Optional[str]:
    value = record.get(field_name)
    if isinstance(value, str) and value.strip():
        return value.split("/")[-1]
    if isinstance(value, list):
        for entry in value:
            if isinstance(entry, str) and entry.strip():
                return entry.split("/")[-1]
    return None


def compact_error_text(resp: requests.Response) -> str:
    text = (resp.text or "").strip()
    if not text:
        return "<empty>"
    try:
        data = resp.json()
        if isinstance(data, dict):
            parts = []
            message = data.get("message")
            if isinstance(message, str) and message.strip():
                parts.append(message.strip())
            details = data.get("data")
            if details is not None:
                parts.append(json.dumps(details, ensure_ascii=False))
            if parts:
                return " | ".join(parts)[:1000]
        return text[:500]
    except Exception:
        return text[:500]


def parse_pocketbase_error(resp: requests.Response) -> tuple[Optional[str], Optional[dict]]:
    try:
        payload = resp.json()
        if not isinstance(payload, dict):
            return None, None
        data = payload.get("data")
        if not isinstance(data, dict):
            return None, None
        for _, value in data.items():
            if isinstance(value, dict):
                code = value.get("code")
                if isinstance(code, str):
                    return code, value
    except Exception:
        pass
    return None, None


def fetch_book_record(base_url: str, token: str, record_id: str, verify_ssl: bool) -> dict:
    endpoint = f"{base_url}/api/collections/books/records/{record_id}"
    resp = requests.get(
        endpoint,
        headers=auth_headers(token),
        timeout=30,
        verify=verify_ssl,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"Failed to fetch record {record_id}: {resp.status_code} {compact_error_text(resp)}")
    return resp.json() if resp.text else {}


def upload_epub(
    base_url: str,
    token: str,
    user_id: str,
    record_id: str,
    epub_path: Path,
    verify_ssl: bool,
    field_candidates: Iterable[str] = FILE_FIELD_CANDIDATES,
    mime_candidates: Iterable[str] = MIME_CANDIDATES,
) -> Tuple[str, str]:
    endpoint = f"{base_url}/api/collections/books/records/{record_id}"
    filename = epub_path.name
    headers = auth_headers(token)
    last_error_code: Optional[str] = None
    last_error_payload: Optional[dict] = None

    for field in field_candidates:
        for mime in mime_candidates:
            with epub_path.open("rb") as f:
                files = {field: (filename, f, mime)}
                resp = requests.patch(
                    endpoint,
                    data={
                        "user": user_id,
                        "updatedAt": str(int(time.time() * 1000)),
                    },
                    files=files,
                    headers=headers,
                    timeout=120,
                    verify=verify_ssl,
                )
            if resp.status_code >= 300:
                err_code, err_payload = parse_pocketbase_error(resp)
                if err_code:
                    last_error_code = err_code
                    last_error_payload = err_payload
                print(
                    f"[warn] upload field '{field}' mime='{mime}' failed: "
                    f"{resp.status_code} {compact_error_text(resp)}"
                )
                continue

            data = resp.json() if resp.text else {}
            uploaded = extract_uploaded_file_name(data, field)
            if uploaded:
                return field, uploaded

            # Some PocketBase setups may return sparse payloads; verify with a follow-up GET.
            try:
                fetched = fetch_book_record(base_url, token, record_id, verify_ssl=verify_ssl)
                uploaded = extract_uploaded_file_name(fetched, field)
                if uploaded:
                    return field, uploaded
            except Exception as e:
                print(f"[warn] post-upload fetch failed for field '{field}': {e}")

            print(
                f"[warn] field '{field}' mime='{mime}' accepted but no filename in "
                "response/record"
            )

    if last_error_code == "validation_file_size_limit":
        max_size = None
        if isinstance(last_error_payload, dict):
            params = last_error_payload.get("params")
            if isinstance(params, dict):
                max_size = params.get("maxSize")
        raise RuntimeError(
            "Upload failed: books.bookFile max file size is too small "
            f"(current maxSize={max_size}). Increase it in PocketBase Admin."
        )

    raise RuntimeError(
        "Upload failed on all candidate fields. "
        "Check books collection has a File field (recommended: bookFile)."
    )


def patch_storage_path(base_url: str, token: str, record_id: str, storage_path: str, verify_ssl: bool) -> None:
    endpoint = f"{base_url}/api/collections/books/records/{record_id}"
    payload = {"storagePath": storage_path, "updatedAt": int(time.time() * 1000)}
    resp = requests.patch(
        endpoint,
        json=payload,
        headers=auth_headers(token),
        timeout=30,
        verify=verify_ssl,
    )
    if resp.status_code >= 300:
        raise RuntimeError(f"Failed to patch storagePath: {resp.status_code} {resp.text[:400]}")


def get_file_token(base_url: str, token: str, verify_ssl: bool) -> Optional[str]:
    endpoint = f"{base_url}/api/files/token"
    resp = requests.post(
        endpoint,
        json={},
        headers=auth_headers(token),
        timeout=30,
        verify=verify_ssl,
    )
    if resp.status_code != 200:
        return None
    data = resp.json() if resp.text else {}
    value = (data.get("token") or "").strip()
    return value or None


def verify_download(
    base_url: str,
    token: str,
    record_id: str,
    uploaded_name: str,
    local_sha256: str,
    verify_ssl: bool,
    file_token: Optional[str] = None,
) -> None:
    rid = quote(record_id, safe="")
    fname = quote(uploaded_name, safe="")
    download_url = f"{base_url}/api/files/books/{rid}/{fname}"
    if file_token:
        download_url = f"{download_url}?token={quote(file_token, safe='')}"
    resp = requests.get(
        download_url,
        headers=auth_headers(token),
        timeout=120,
        verify=verify_ssl,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"Download verification failed: {resp.status_code} {resp.text[:400]}")

    remote_sha = hashlib.sha256(resp.content).hexdigest()
    if remote_sha != local_sha256:
        raise RuntimeError(
            "Hash mismatch after upload/download. "
            f"local={local_sha256} remote={remote_sha}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Upload and verify EPUB in PocketBase books collection.")
    parser.add_argument("--url", help="PocketBase base URL (e.g. https://pb.example.com)")
    parser.add_argument("--email", help="PocketBase user email")
    parser.add_argument("--password", help="PocketBase user password")
    parser.add_argument("--epub", default="test.epub", help="EPUB file path (default: test.epub)")
    parser.add_argument("--title", default="Upload Verification Book", help="Book title to store in metadata")
    parser.add_argument("--field", help="Force a specific file field name (e.g. bookFile)")
    parser.add_argument("--insecure", action="store_true", help="Disable SSL verification")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    file_env = load_env_file(repo_root / ".env")

    base_url = resolve_value(args.url, "POCKETBASE_URL", file_env)
    email = resolve_value(args.email, "POCKETBASE_TEST_EMAIL", file_env)
    password = resolve_value(args.password, "POCKETBASE_TEST_PASSWORD", file_env)
    epub_path = (repo_root / args.epub).resolve() if not Path(args.epub).is_absolute() else Path(args.epub)

    if not base_url:
        print("ERROR: POCKETBASE_URL is required (--url or env/.env).")
        return 1
    if not email or not password:
        print(
            "ERROR: credentials required. Provide --email/--password "
            "or set POCKETBASE_TEST_EMAIL / POCKETBASE_TEST_PASSWORD in env/.env."
        )
        return 1
    if not epub_path.exists():
        print(f"ERROR: EPUB file not found: {epub_path}")
        return 1

    verify_ssl = not args.insecure
    base_url = base_url.rstrip("/")
    local_hash = sha256_of_file(epub_path)
    print(f"[info] base_url={base_url}")
    print(f"[info] epub={epub_path}")
    print(f"[info] sha256={local_hash}")

    try:
        token, user_id = auth_user(base_url, email, password, verify_ssl=verify_ssl)
        print(f"[ok] authenticated as user={user_id}")

        record_id = upsert_book_record(
            base_url=base_url,
            token=token,
            user_id=user_id,
            book_id=local_hash,
            title=args.title,
            verify_ssl=verify_ssl,
        )
        print(f"[ok] books record ready id={record_id}")
        fetched = fetch_book_record(
            base_url=base_url,
            token=token,
            record_id=record_id,
            verify_ssl=verify_ssl,
        )
        has_book_file_field = "bookFile" in fetched
        print(f"[info] books record has 'bookFile' field: {has_book_file_field}")
        if has_book_file_field:
            print(f"[info] current bookFile value: {fetched.get('bookFile')!r}")

        field_used, uploaded_name = upload_epub(
            base_url=base_url,
            token=token,
            user_id=user_id,
            record_id=record_id,
            epub_path=epub_path,
            verify_ssl=verify_ssl,
            field_candidates=(args.field,) if args.field else FILE_FIELD_CANDIDATES,
        )
        storage_path = f"{record_id}/{uploaded_name}"
        print(f"[ok] uploaded via field={field_used} file={uploaded_name}")

        patch_storage_path(
            base_url=base_url,
            token=token,
            record_id=record_id,
            storage_path=storage_path,
            verify_ssl=verify_ssl,
        )
        print(f"[ok] storagePath set to {storage_path}")

        file_token = get_file_token(
            base_url=base_url,
            token=token,
            verify_ssl=verify_ssl,
        )
        if file_token:
            print("[ok] acquired protected file token")
        else:
            print("[warn] could not acquire protected file token, trying download without token")

        verify_download(
            base_url=base_url,
            token=token,
            record_id=record_id,
            uploaded_name=uploaded_name,
            local_sha256=local_hash,
            verify_ssl=verify_ssl,
            file_token=file_token,
        )
        print("[ok] download verification passed (sha256 matched)")
        return 0
    except Exception as exc:
        print(f"ERROR: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
