## 2025-05-18 - Fallback Master Key in TokenManager
**Vulnerability:** In `TokenManager.kt`, if MasterKeys creation fails, it falls back to a hardcoded string `"_fallback_master_key_"`. This is a critical vulnerability because an attacker could extract this key from the app and use it to decrypt the tokens.
**Learning:** Hardcoded cryptographic keys defeat the purpose of encryption, turning it into easily reversible obfuscation.
**Prevention:** Avoid falling back to hardcoded keys. If key generation fails, it should throw an exception to let the caller fallback to explicitly unencrypted shared preferences rather than giving a false sense of security with a hardcoded cryptographic alias.

## 2025-05-18 - Auth Interceptor Token Leakage
**Vulnerability:** The OkHttpClient `AuthInterceptor` unconditionally added the `Authorization: Bearer <token>` header to all requests that were not explicitly tagged with `SKIP_AUTH`. This causes the user's access token to be leaked to third-party services (like the Gemini API) if the same OkHttpClient instance is shared.
**Learning:** Global HTTP interceptors that inject credentials must always validate the destination host to prevent unintentional token disclosure to third-party domains.
**Prevention:** Implement host validation in authentication interceptors by comparing `originalRequest.url.host` against the expected backend host before attaching the authorization header.
