# <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="Boox Reader icon" align="left" /> Boox Reader

Private EPUB reader for Android with resilient cloud sync and AI-linked notes, tuned for e-ink devices (Boox).

[Google Play](https://play.google.com/store/apps/details?id=my.hinoki.booxreader) ·
[GitHub Releases (APK)](https://github.com/pjiaquan/booxreader/releases) ·
[Privacy Policy](docs/privacy.html)

## Highlights

- EPUB reading tuned for e-ink (reduced motion + refresh controls)
- Sync progress, bookmarks, and notes via Firestore
- Backup EPUB files via Firebase Storage (checksum-based)
- Improved language/i18n support across locales

## Build

- Debug: `./gradlew :app:assembleDebug`
- Release: `./gradlew :app:assembleRelease`

## Install to device (ADB)

- Debug: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Release: `adb install -r app/build/outputs/apk/release/app-release.apk`

## Helper script

`./run.sh` automates building + installing. It only uninstalls when a signing-key mismatch is confirmed (or when Android reports a signature incompatibility during install).

If you want signing-key verification before install, ensure `apksigner` is available:

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export PATH="$ANDROID_SDK_ROOT/build-tools/35.0.0:$PATH"
apksigner version
```

