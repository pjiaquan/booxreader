## 2025-05-18 - Fallback Master Key in TokenManager
**Vulnerability:** In `TokenManager.kt`, if MasterKeys creation fails, it falls back to a hardcoded string `"_fallback_master_key_"`. This is a critical vulnerability because an attacker could extract this key from the app and use it to decrypt the tokens.
**Learning:** Hardcoded cryptographic keys defeat the purpose of encryption, turning it into easily reversible obfuscation.
**Prevention:** Avoid falling back to hardcoded keys. If key generation fails, it should throw an exception to let the caller fallback to explicitly unencrypted shared preferences rather than giving a false sense of security with a hardcoded cryptographic alias.
## 2025-05-18 - Cleartext traffic enabled
**Vulnerability:** The app enables cleartext traffic globally in `AndroidManifest.xml` (`android:usesCleartextTraffic="true"`) and also overrides it for a specific domain in `network_security_config.xml` (`cleartextTrafficPermitted="true"`). This allows the app to communicate over unencrypted HTTP connections, exposing user data to interception and manipulation.
**Learning:** Cleartext traffic should be disabled by default to ensure all communication occurs over secure HTTPS connections.
**Prevention:** Remove `android:usesCleartextTraffic="true"` from the manifest and disable it in the network security config unless strictly necessary for specific, isolated use cases (like local network debugging).

## 2025-05-18 - Auth Interceptor Token Leakage
**Vulnerability:** The OkHttpClient `AuthInterceptor` unconditionally added the `Authorization: Bearer <token>` header to all requests that were not explicitly tagged with `SKIP_AUTH`. This causes the user's access token to be leaked to third-party services (like the Gemini API) if the same OkHttpClient instance is shared.
**Learning:** Global HTTP interceptors that inject credentials must always validate the destination host to prevent unintentional token disclosure to third-party domains.
**Prevention:** Implement host validation in authentication interceptors by comparing `originalRequest.url.host` against the expected backend host before attaching the authorization header.


## 2025-05-24 - Silent security fallback in EncryptedSharedPreferences
**Vulnerability:** `TokenManager.kt` was silently falling back to a plaintext `SharedPreferences` when `EncryptedSharedPreferences` failed to initialize (e.g. due to Keystore corruption). This stored sensitive user access tokens and refresh tokens completely unencrypted without any warning.
**Learning:** Catching cryptographic exceptions and falling back to a less secure method creates a dangerous downgrade attack vector and gives a false sense of security.
**Prevention:** If secure storage mechanisms fail, the application must throw an exception or fail securely, ensuring sensitive credentials are never stored in plaintext by accident.

## 2025-05-24 - Network Security Config Cleartext Protection
**Vulnerability:** The application was missing a `<base-config cleartextTrafficPermitted="false" />` declaration in `network_security_config.xml`, leaving older Android versions potentially vulnerable to unencrypted HTTP traffic outside of explicit domain blocks.
**Learning:** Always apply a global `base-config` deny-by-default for cleartext traffic as a defense-in-depth measure.
**Prevention:** Ensure `network_security_config.xml` includes `<base-config cleartextTrafficPermitted="false" />`.
## 2026-08-19 - Proper Bearer Token Formatting\n**Vulnerability:** In `PocketBaseRealtimeClient.kt`, the raw access token was being sent in the `Authorization` header instead of the standard `Bearer <token>` format.\n**Learning:** While some specific backends may be forgiving, failing to use the standard `Bearer` prefix can cause authentication failures when interacting with strict reverse proxies, WAFs, or standard server implementations.\n**Prevention:** Always prepend `Bearer ` to access tokens in the `Authorization` header for standard OAuth2/JWT authentication schemes.\n

## 2026-08-19 - Response Body Data Leakage in Logs
**Vulnerability:** The application was logging raw HTTP response bodies and error bodies directly to Logcat via `Log.e` on authentication endpoints (e.g. `Login failed: $responseBody`). This is a critical security vulnerability as it can leak sensitive information like PII, stack traces, and session tokens to device logs accessible by other apps or debugging tools.
**Learning:** Never log raw HTTP response bodies or error payloads from authentication or sensitive endpoints.
**Prevention:** Always log generic error messages alongside the HTTP status code (e.g., `Login failed with code: ${response.code}`) to aid in debugging without exposing sensitive data.

## 2024-05-24 - Do Not Expose HTTP Raw Response Body in Logs
**Vulnerability:** The application was logging raw HTTP response bodies (`body=$body` or `Body=$errorBody`) when remote API requests or AI explanation requests failed (e.g., in `executeRequest` and `fetchAiExplanation`).
**Learning:** These error responses from backend APIs or LLM providers often mirror request parameters, internal identifiers, or even API keys and authentication tokens, which can leak into local device logs or crash reporting tools.
**Prevention:** Always log generic error messages alongside the HTTP status code (e.g., `Log.e(TAG, "AI Request Failed: Code=${response.code}")`). Avoid appending `response.body?.string()` to log statements or exception messages.

## 2026-08-19 - Intent Scheme Hijacking via External Links
**Vulnerability:** `NativeNavigatorFragment` and `AiNoteDetailActivity` were directly passing external, untrusted URLs to `Intent.ACTION_VIEW` without validating the URI scheme.
**Learning:** This exposes the app to Intent Scheme Hijacking, allowing malicious content (e.g., in EPUBs or notes) to launch arbitrary apps or access local files using custom schemes like `intent://` or `file://`.
**Prevention:** Always validate URI schemes (allowlisting `http` and `https`) before passing untrusted external URLs to implicit intents like `Intent.ACTION_VIEW`.
