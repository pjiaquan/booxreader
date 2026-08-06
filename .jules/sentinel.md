## 2025-05-18 - Fallback Master Key in TokenManager
**Vulnerability:** In `TokenManager.kt`, if MasterKeys creation fails, it falls back to a hardcoded string `"_fallback_master_key_"`. This is a critical vulnerability because an attacker could extract this key from the app and use it to decrypt the tokens.
**Learning:** Hardcoded cryptographic keys defeat the purpose of encryption, turning it into easily reversible obfuscation.
**Prevention:** Avoid falling back to hardcoded keys. If key generation fails, it should throw an exception to let the caller fallback to explicitly unencrypted shared preferences rather than giving a false sense of security with a hardcoded cryptographic alias.

## 2025-05-18 - Global OkHttpClient Token Leakage
**Vulnerability:** The global `OkHttpClient` used in `BooxReaderApp` was configured with an `AuthInterceptor` that appended the Authorization Bearer token to *all* outgoing requests. This posed a risk of token leakage if the client was used to make requests to third-party domains (e.g. for fetching external book metadata or images).
**Learning:** When using a shared `OkHttpClient` for both API and external requests, global auth interceptors must validate the request destination to avoid leaking credentials.
**Prevention:** Always validate `originalRequest.url.host` against the expected backend host before attaching authentication headers in global interceptors.
