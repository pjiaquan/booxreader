## 2025-05-18 - Fallback Master Key in TokenManager
**Vulnerability:** In `TokenManager.kt`, if MasterKeys creation fails, it falls back to a hardcoded string `"_fallback_master_key_"`. This is a critical vulnerability because an attacker could extract this key from the app and use it to decrypt the tokens.
**Learning:** Hardcoded cryptographic keys defeat the purpose of encryption, turning it into easily reversible obfuscation.
**Prevention:** Avoid falling back to hardcoded keys. If key generation fails, it should throw an exception to let the caller fallback to explicitly unencrypted shared preferences rather than giving a false sense of security with a hardcoded cryptographic alias.

## 2025-05-18 - Global Interceptor Token Leakage
**Vulnerability:** `AuthInterceptor` attached the PocketBase access token to every outgoing request using the shared `OkHttpClient`, regardless of the destination domain. When external requests (e.g., fetching a remote EPUB file from a different domain) were made using this client, the backend access token was sent to the third-party domain, exposing the user's credentials.
**Learning:** Using a global `OkHttpClient` interceptor to attach authentication headers without validating the destination domain is a common pitfall that leads to token leakage.
**Prevention:** Always validate `originalRequest.url.host` against the expected backend's host before attaching sensitive `Authorization` headers in a network interceptor.
