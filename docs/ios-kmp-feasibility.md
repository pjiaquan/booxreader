# BooxReader iOS 移植可行性分析（Kotlin Multiplatform / Compose Multiplatform）

> 分析日期：2026-08-22 ｜ 對象：`pjiaquan/booxreader`（Android App）
> 目標：評估將現有 Android EPUB 閱讀器移植到 iPhone 的可行性、架構、工作量與風險

---

## 1. 結論摘要

| 項目 | 評估 |
|---|---|
| **可行性** | ✅ 可行。Kotlin 技術棧 + KMP 生態成熟（Room 2.8.4 已官方支援 KMP） |
| **建議路徑** | Kotlin Multiplatform：抽取 `:shared` 共用層 + Compose Multiplatform 重寫 UI |
| **程式碼共用率** | 資料層約 **65–75%** 可直接共用；UI 層（18 個 XML layout）**需全部重寫**為 Compose |
| **總工作量** | 約 **35–55 人天**（單人 2–3 個月，含 iOS 整合與測試） |
| **最大風險** | 閱讀器 UI（~5,000 LOC 的 XML View 互動程式）重寫 + Apple 簽名/上架流程 |
| **前置需求** | Mac + Xcode、Apple Developer 帳號（$99/年）或免費帳號 7 天簽名 |

**關鍵好消息**：
- 閱讀引擎 [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) 3.1.2 本身就是 KMP 專案，**支援 iOS target**，且有 [Compose 整合討論](https://github.com/readium/kotlin-toolkit/discussions/552)——閱讀核心不必重寫。
- [Room 2.8.4 起已重構為 KMP 程式庫](https://developer.android.com/jetpack/androidx/releases/room)，現有 `@Entity`/`@Dao`/`@Database` 程式碼幾乎可原封不動搬進 shared 層。
- 雲端同步（Supabase/PocketBase REST + SSE）是純 HTTP 邏輯，跨客戶端共用同一份資料。

---

## 2. 程式碼現況盤點

| 指標 | 數值 |
|---|---|
| Kotlin 檔案數（`app/src/main`） | 71 |
| 程式行數 | ~22,200 |
| XML layout | 18 |
| Compose UI 使用 | **0**（build.gradle 有配置，但沒有任何 `androidx.compose` import） |
| Activity / Fragment | 12 個 Activity（+BaseActivity）+ Reader 的 nativev2 元件 |

**資料層（可共用度最高）**
- `data/db`（12 檔）：Room Entity/Dao —— KMP 相容 ✅
- `data/remote`（11 檔）：OkHttp 網路層（AuthInterceptor、SSE/Realtime client、Publisher）—— 換 HTTP client 即可共用 ✅
- `data/repo`（10 檔）：業務邏輯（Book/UserSync/AiNote/Auth/Profile）—— 高度可共用 ✅
- `data/settings`、`data/core`、`data/prefs`：設定與工具 —— 部分需平台抽象

**UI 層（需重寫）**
- 13 個 Activity、18 個 XML layout
- 閱讀器最重：`ReaderActivity.kt`（1,682 行）+ `nativev2/`（4,919 行：NativeNavigatorFragment 1,403、NativeReaderView 1,189、HtmlContentParser 549）

---

## 3. Android-only API 盤點與 iOS 對應

| Android API | 使用檔案數 | iOS 對應方案 | 工作量 |
|---|---|---|---|
| `android.content.Context` | 30 | expect/actual 平台抽象（不直接替換，抽介面） | 中 |
| `SharedPreferences` | 20 | [multiplatform-settings](https://github.com/russhwolf/multiplatform-settings) | 低 |
| `android.net.Uri` / `Intent` | 16 / 14 | 平台抽象（`expect` + iOS `URL`） | 中 |
| `Toast` | 15 | 平台通知（SwiftUI overlay / snackbar） | 低 |
| `ContentResolver`（檔案存取） | 9 | iOS `UIDocumentPicker` / 檔案系統 | 中 |
| Room | 13 | Room 2.8.x KMP（已官方支援） | 低 |
| WorkManager（每日摘要排程） | 2 | iOS `BGTaskScheduler` | 低 |
| Google Sign-In（play-services） | 2 | GoogleSignIn iOS SDK | 低 |
| androidx.security（EncryptedSharedPreferences） | 1 | iOS Keychain | 低 |
| FileProvider | 1 | 不需要（iOS 無此機制） | 低 |

---

## 4. 第三方依賴評估

| 依賴 | 現況版本 | KMP/iOS 支援 | 處置 |
|---|---|---|---|
| Readium Kotlin Toolkit | 3.1.2 | ✅ 支援 iOS | **保留**，共用閱讀核心 |
| kotlinx-coroutines / datetime | — | ✅ | 保留 |
| **OkHttp** | 4.12.0 | ❌ JVM-only（5.x 亦不支援 KMP） | 改用 **Ktor client**（15 個檔案需改 import + 少量 API） |
| **Gson** | 2.10.1 | ❌ JVM-only | 改用 **kotlinx.serialization**（11 個檔案，需加 `@Serializable`） |
| **Room** | 2.7.0-rc01 | ⚠️ 舊版 Android-only | 升 **2.8.4+**（KMP 版），Entity/Dao 幾乎不用改 |
| **markwon**（Markdown） | 4.6.2 | ❌ Android-only | Compose 的 Markdown 函式庫（如 compose-markdown） |
| **opencc4j**（簡繁轉換） | 1.8.1 | ❌ JVM-only | opencc-js（KMP wrapper）或自建對照表 |
| AWS SDK Kotlin（R2） | — | ✅ KMP | 目前程式已改用 Supabase Storage，實際未使用 |

---

## 5. 建議架構

```
booxreader/
├── :shared          ← KMP 共用層（新增）
│   ├── commonMain   → data/db, data/repo, data/remote, data/settings, 領域邏輯
│   ├── androidMain  → 現有 Android 平台實作（Context、檔案、Keychain 橋接）
│   └── iosMain      → iOS 平台實作（Keychain、UIDocumentPicker、BGTask）
├── :app             ← 現有 Android App（改為依賴 :shared，UI 維持 XML 或逐步換 Compose）
└── :composeApp      ← 新增 iOS App 入口（Compose Multiplatform UI + 閱讀器畫面）
```

**遷移策略（推薦）**：不重寫 Android 版，而是「抽出共用層 → Android 改引用 :shared → 另建 iOS Compose App」。Android 版持續可用，iOS 版與 Android 版共用同一份業務邏輯。

---

## 6. 階段計畫與人天估算

| 階段 | 內容 | 人天 |
|---|---|---|
| 0. 前置準備 | Mac + Xcode 環境、Apple 帳號、KMP 專案骨架、CI 設定 | 2–3 |
| 1. 抽取 :shared | 資料層（db/remote/repo/settings）移入 commonMain，OkHttp→Ktor、Gson→kotlinx.serialization | 8–12 |
| 2. 平台層 | SharedPreferences→multiplatform-settings、Keychain、檔案存取抽象、expect/actual | 4–6 |
| 3. 閱讀引擎 | Readium iOS target 整合、導出 EPUB 內容、分頁/進度/書籤邏輯對接 | 5–8 |
| 4. UI 重寫 | Compose Multiplatform 重建：登入/首頁/書庫/閱讀器/設定/個人資料（13 個畫面） | 12–18 |
| 5. iOS 整合 | Xcode 工程、簽名、後台任務、TestFlight、裝置測試 | 4–8 |
| **合計** | | **35–55 人天** |

---

## 7. 風險與緩解

| 風險 | 等級 | 緩解 |
|---|---|---|
| 閱讀器 UI（~5k LOC）重寫複雜度高 | 🔴 高 | 先以最簡可讀版本上線，再逐步補齊（翻頁動畫、對比模式等） |
| Room KMP 為新功能 | 🟡 中 | 現有 DAO 語法相容；不兼容處改用 SQLDelight |
| Ktor 與 OkHttp 行為差異（SSE/攔截器） | 🟡 中 | 先做網路層整合測試；SSE 邏輯封裝在單一 client |
| iOS 簽名/上架流程 | 🟡 中 | 不需上架 App Store：TestFlight（測試員 90 天）或免費帳號 7 天簽名直裝 |
| e-ink 調校功能在 iPhone 無意義 | 🟢 低 | 視為選配，不移植 |

---

## 8. 前置需求（開始前必須具備）

1. **Mac（Apple Silicon 佳）+ Xcode**（KMP iOS 建置只能在 macOS）
2. **Apple Developer 帳號**：
   - 免費 Apple ID：可簽名直裝到自己的 iPhone，但 **7 天過期需重簽**
   - 付費 Developer Program（$99/年）：TestFlight（最多 90 天測試）、正式安裝不需重簽
3. （建議）GitHub Actions 加一台 macOS runner 做 iOS 建置

---

## 9. 附註

- 目前 UI 全部是 XML View（Compose 雖已配置但未使用），因此 UI 重寫是全新工作，無法複用既有 layout。
- 若只要「在 iPhone 上讀同一份書庫」，**PWA 網頁版**是成本更低的替代（接同一 Supabase/PocketBase 資料）；KMP 移植適合想要原生 App 體驗的長期目標。
