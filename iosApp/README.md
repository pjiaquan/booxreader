# iosApp — iOS 殼（Phase 4）

SwiftUI 殼，整合 `:shared` 的 Kotlin Multiplatform framework（`Shared`）。

## 結構

```
iosApp/
├── project.yml          # XcodeGen 規格（產生 iosApp.xcodeproj）
└── iosApp/
    ├── iOSApp.swift     # @main 入口
    ├── ContentView.swift      # 三個 Tab：書庫 / 設定 / 個人
    ├── LibraryView.swift      # 從 shared AppDatabase（Room KMP）列出書籍
    ├── SettingsView.swift     # 後端 URL、手動同步、連線檢查
    ├── ProfileView.swift      # 登入狀態（placeholder）
    ├── SharedModule.swift     # KMP suspend → Swift async 橋接 + 依賴組裝
    ├── Info.plist
    └── Assets.xcassets
```

## 在 macOS 上建置

前置：Xcode、JDK 21（`brew install --cask temurin`）、XcodeGen（`brew install xcodegen`）。

```bash
# 1. 產生 Xcode 工程
xcodegen generate --spec iosApp/project.yml

# 2. 用 Xcode 開啟 iosApp/iosApp.xcodeproj 直接 Run；
#    或在命令列建置（模擬器，不需簽名）：
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

Xcode 的 **Compile Kotlin Framework** pre-build script 會執行
`./gradlew :shared:embedAndSignAppleFrameworkForXcode`，依 Xcode 環境變數
（`SDK_NAME` / `ARCHS` / `CONFIGURATION`）產出對應平台的 `Shared.framework`，
放在 `shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` 供連結。

## 目前範圍（Phase 4 殼）

- 書庫 Tab：讀取 shared Room 資料庫（BundledSQLiteDriver）並列出書籍
- 設定 Tab：顯示後端 URL、手動 `pullBooks()`、`ensureStorageBucketReady()` 連線檢查
- 個人 Tab：登入狀態 placeholder
- **尚未實作**：閱讀引擎（Readium Swift Toolkit）、登入/註冊 UI、書籍匯入、
  AI 筆記 UI、Keychain token 儲存（目前用 NSUserDefaults）

## 注意

- iosMain Kotlin 程式碼（`shared/src/iosMain`）只能在 macOS 編譯驗證；
  Linux 上無法編譯。CI（`.github/workflows/ios-build.yml`）會在 macOS runner 驗證。
- 預設後端 URL 與 Android 一致（`https://pocket.risc-v.tw`），可經由
  `IosTokenProvider` 的 `pocketbase_backend_url` key 覆寫。
