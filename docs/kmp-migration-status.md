# KMP 移植階段 1 — 狀態與邊界（2026-08-22）

本文記錄 `:shared` KMP 共用模組的完成狀態、平台邊界與後續計畫。

## 1. 已完成：`:shared` 共用層

### 模組設定
- `androidTarget()` + `iosArm64` / `iosSimulatorArm64` / `iosX64`（framework 名稱 `Shared`，靜態連結）
- commonMain 依賴：kotlinx-serialization-json、kotlinx-coroutines-core、ktor-client-core、Room 2.8.4（KMP）
- androidMain：ktor-client-okhttp、androidx.sqlite `sqlite-framework`
- iosMain：ktor-client-darwin、androidx.sqlite `sqlite-bundled`

### commonMain 內容（17 個檔案）

| 套件 | 內容 | 替換 |
|---|---|---|
| `data/db` | 5 Entities + 5 DAOs + `AppDatabase`（Room KMP） | Room 2.7→2.8.4 |
| `data/settings` | `ReaderSettings`、`ContrastMode`、`MagicTag`、`KeyValueStorage` | SharedPreferences+Gson→介面+kotlinx |
| `data/remote` | `HttpConfig`、payloads、`ProgressPublisher`、`BookmarkPublisher`、`AiModelFetcher`、`StreamingErrorHandler` | OkHttp→Ktor、Gson/org.json→kotlinx |
| `data/repo` | `GitHubUpdateModels`、`AiProfileDefaultGenerator` | Gson→kotlinx |
| `data/core/utils` | `AiNoteSerialization` | org.json→kotlinx |
| `data/auth` | `TokenProvider`（token 抽象） | 新介面 |
| `data/platform` | `currentEpochMillis()` expect | System.currentTimeMillis 抽象 |

### 平台實作（expect/actual）

| 抽象 | androidMain | iosMain |
|---|---|---|
| `currentEpochMillis()` | `System.currentTimeMillis()` | `NSDate` |
| `KeyValueStorage` | `SharedPreferencesStorage` | `NSUserDefaultsStorage` |
| `AppDatabase` 建置 | `Room.databaseBuilder(context)` + legacy migrations（含 AI 筆記回填） | `Room.databaseBuilder + BundledSQLiteDriver`（新裝即 v14） |

## 2. 平台邊界（刻意保留在 :app，屬 Phase 2）

| 項目 | 原因 |
|---|---|
| `UserSyncRepository`、`AiNoteRepository` 等大型 repos | 依賴 Context（prefs/檔案）+ 注入的 OkHttpClient；`UserSyncRepository` 的 Gson 動態 `Map<String,Any>` 解析無法無痛對應 kotlinx |
| SSE / realtime clients（`PocketBaseSseClient` 等） | OkHttp SSE 事件模型與 Ktor SSE 不同；重連邏輯無法在本機（無模擬器）驗證行為 |
| `BookRepository`、閱讀相關 | 依賴 **Readium Kotlin Toolkit（Maven 僅有 Android artifact，無 iOS klib）** — 見可行性文件之更正 |
| `LocatorJsonHelper` | org.json + Readium API 橋接（Readium Android-only） |
| `TokenManager` | EncryptedSharedPreferences（Android 安全儲存）；已實作 `TokenProvider` 介面供共用層使用 |

## 3. 驗證狀態（每步保持 :app 可編譯）

- `:shared:compileDebugKotlinAndroid` ✅（Room KMP + KSP2）
- `:app:assembleDebug` ✅
- `:app:testDebugUnitTest` ✅（含 crash-report、MagicTag 序列化、DB migration 測試）
- `:app:assembleRelease`（R8 + 簽名）✅
- iOS targets：宣告完成；編譯需 macOS（iosMain 檔案已就緒）

## 4. 後續（Phase 2，若繼續）

1. **app 層 HTTP 改 Ktor**：repos 改用 Ktor client → 解除 OkHttp 依賴後，SSE 與 repos 可逐步搬入 shared
2. **`UserSyncRepository` Gson→kotlinx**：需將 `PocketBaseListResponse.items: List<Map<String,Any>>` 改為 typed models 或 `JsonObject`（~85 處 cast）
3. **iOS 端**：macOS 上驗證 iosMain 編譯、建置 Xcode 工程、接入 Readium Swift Toolkit


## 5. Phase 2 完成狀態（2026-08-22 追加）

### ✅ 已完成
| 項目 | 狀態 |
|---|---|
| **App HTTP 層 100% Ktor** | `app/src/main/java` 內 okhttp3 引用數為 **0**；BooxReaderApp 的 OkHttpClient 已移除 |
| **Repos → Ktor** | GitHubUpdateRepository、AuthRepository、UserSyncRepository、AiNoteRepository（含 SSE 串流）全部改用 shared `createApiClient()` |
| **SSE → Ktor** | PocketBaseRealtimeClient / PocketBaseSseClient 改用 `serverSentEventsSession` |
| **Auth 抽象** | shared `BearerAuth` plugin（onRequest）取代 OkHttp AuthInterceptor；`TokenProvider` 介面 |
| **Gson 完全移除** | UserSyncRepository 動態 Map → `List<JsonObject>` + kotlinx accessors；`google-gson` 依賴刪除 |
| **Repo 純邏輯搬入 shared** | GitHubUpdateModels、AiProfileDefaultGenerator（Phase 1）、**GitHubUpdateChecker**（Phase 2，版本檢查邏輯） |

### ⛔ 平台邊界（無法搬入 commonMain 的原因）
| Repo | 阻礙 |
|---|---|
| BookRepository / BookmarkRepository | Readium（Maven 僅 Android artifact，無 iOS klib） |
| UserSyncRepository / AiNoteRepository / AuthRepository / AiProfileRepository | Context 檔案 I/O（contentResolver、MediaStore、getExternalFilesDir）+ 彼此依賴 + ErrorReporter |
| GitHubUpdateRepository（下載/安裝部分） | FileProvider / Intent / getExternalFilesDir |

要搬移上述 repos 需先建立 Phase 3 的**平台檔案存取 expect/actual**（content URI 讀取、暫存檔、MediaStore 儲存）與 ErrorReporter/logger 抽象。

## 6. Phase 3 完成狀態（2026-08-22 追加）

### ✅ 已完成
| 項目 | 狀態 |
|---|---|
| **PlatformFiles expect/actual** | 14 個方法：readUriBytes / writeCacheFile / appFilesDir / isUriReadable / contentName / exists / mkdirs / writeFile / readFile / delete / rename / fileLength / readFilePrefix / contentType / writeDownloadsFile（含 DownloadsWriteResult） |
| **Reporter / Logger 抽象** | `Reporter`、`Logger` 介面（commonMain）；`AndroidReporter`、`AndroidLogger`（:app）；UserSyncRepository 的 Log/ErrorReporter 全部換掉 |
| **KeyValueStorage 擴充** | `clearAll()`；`TokenProvider` 增加 saveAccessToken / clearTokens |
| **UserSyncRepository 搬入 shared** | 移除 contentResolver / Uri / File / URLEncoder / Locale；`ensureBookFileAvailable` 回傳 `file://` 字串；`CrashReport` data class 移至 commonMain；`createUserSyncRepository(context,…)` factory（33 呼叫點） |
| **AuthRepository 搬入 shared** | avatar 上傳改用 PlatformFiles；`createAuthRepository(context, tokenManager)` factory；logout 注入 syncRepo |
| **AiProfileRepository 搬入 shared** | LiveData→Flow（`allProfiles`）；prefs→KeyValueStorage；`createAiProfileRepository` factory |
| **AiNoteRepository 搬入 shared** | org.json 107 處→kotlinx.serialization（buildJsonObject/buildJsonArray/JsonNull/optString 輔助）；MediaStore 本地匯出→`PlatformFiles.writeDownloadsFile`；prefs→KeyValueStorage；BuildConfig.POCKETBASE_URL→建構參數；`createAiNoteRepository` factory |
| **ReaderViewModel.openBook** | 移除 contentResolver 參數 |

### 目前仍在 :app 的 repos
| Repo | 阻礙 |
|---|---|
| BookRepository / BookmarkRepository | Readium Kotlin Toolkit（Maven 僅 Android artifact，無 iOS klib） |
| GitHubUpdateRepository | FileProvider / Intent / getExternalFilesDir（下載/安裝部分） |

### 驗證（Phase 3）
- `:shared:compileDebugKotlinAndroid` ✅
- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` ✅（120 tests）
- `:app:assembleDebug` / `:app:assembleRelease`（R8）✅
- iOS targets：iosMain 已補齊 PlatformFiles 全部方法，仍待 macOS 驗證編譯

### 已知取捨
- `clearPersistedBookUriPermissions`（登出時清除 Android 持久化 URI 授權）已刪除 → 登出後舊 URI grant 可能殘留
- AiProfileRepository 的「Imported Profile」名稱由 string resource 改為常數字串
- AiNoteRepository 本地匯出統一走 `PlatformFiles.writeDownloadsFile`（Android API 29+ 走 MediaStore Downloads；舊版權限檢查邏輯由 androidMain 封裝）
