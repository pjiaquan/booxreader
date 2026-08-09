## 2025-05-18 - Fallback Master Key in TokenManager
**Vulnerability:** In `TokenManager.kt`, if MasterKeys creation fails, it falls back to a hardcoded string `"_fallback_master_key_"`. This is a critical vulnerability because an attacker could extract this key from the app and use it to decrypt the tokens.
**Learning:** Hardcoded cryptographic keys defeat the purpose of encryption, turning it into easily reversible obfuscation.
**Prevention:** Avoid falling back to hardcoded keys. If key generation fails, it should throw an exception to let the caller fallback to explicitly unencrypted shared preferences rather than giving a false sense of security with a hardcoded cryptographic alias.
## 2025-05-18 - Cleartext traffic enabled
**Vulnerability:** The app enables cleartext traffic globally in `AndroidManifest.xml` (`android:usesCleartextTraffic="true"`) and also overrides it for a specific domain in `network_security_config.xml` (`cleartextTrafficPermitted="true"`). This allows the app to communicate over unencrypted HTTP connections, exposing user data to interception and manipulation.
**Learning:** Cleartext traffic should be disabled by default to ensure all communication occurs over secure HTTPS connections.
**Prevention:** Remove `android:usesCleartextTraffic="true"` from the manifest and disable it in the network security config unless strictly necessary for specific, isolated use cases (like local network debugging).
