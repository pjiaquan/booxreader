# 建立 `annotations` collection（畫線 / 註記同步）

> 適用於 PocketBase **0.23+**（`/api/admins/*` 已移除，superuser 端點為
> `/api/collections/_superusers/*`）。

## 為什麼需要手動建立

畫線（highlight）與註記的同步程式碼在 App 端已經完成（`AnnotationSync` +
`AnnotationRepository`），但 PocketBase 上原本**沒有** `annotations` collection，
所以同步會失敗。建立之後，本機既有的畫線會在下次完整同步時自動補推上去。

**在建立之前不會有任何壞掉的行為** —— 畫線照常存在本機（`isSynced = false`），
只是不會跨裝置。

## 前置條件

- 能以 **superuser** 登入 PocketBase 管理介面：`https://<你的網域>/_/`
- （若忘記 superuser 密碼，在跑 PocketBase 的機器上執行
  `./pocketbase superuser upsert <email>`，它會互動式提示輸入新密碼）

---

## 方法 A：用管理介面點選（建議，最不容易出錯）

1. 登入 `https://<你的網域>/_/`
2. 左側 **Collections** → 右上 **+ New collection**
3. **Name** 填 `annotations`，**Type** 選 **Base** → Create
4. 在欄位編輯區逐一新增下列 8 個欄位（`+ New field`）：

   | Name           | Type     | 設定                                    |
   | -------------- | -------- | --------------------------------------- |
   | `user`         | Relation | Collection 選 **users**、Max select `1`、Required ✓ |
   | `bookId`       | Text     | Required ✓                              |
   | `locatorJson`  | Text     | Required ✓                              |
   | `selectedText` | Text     |                                         |
   | `note`         | Text     |                                         |
   | `style`        | Text     |                                         |
   | `createdAt`    | Number   |                                         |
   | `updatedAt`    | Number   |                                         |

   > 這份規格刻意與既有的 `bookmarks` collection 一致，行為最好預測。
   > `user` 的 relation 請用下拉選單挑 `users`，不要手打 id。

5. 往下找到 **API rules**，五個欄位（List / View / Create / Update / Delete）
   **全部填同一行**：

   ```
   @request.auth.id != "" && user = @request.auth.id
   ```

6. 右上 **Save changes**

---

## 方法 B：匯入 JSON

1. 管理介面 → **Collections** → 右上齒輪／選單 → **Import collections**
2. 先從 **Collections → users** 進去看網址列的 collection id
   （形如 `/collections/_pb_users_auth_` 或 `/collections/pbc_1234567890`），
   把下面 JSON 的 `<USERS_COLLECTION_ID>` 換掉
3. 貼上並匯入：

```json
[
  {
    "name": "annotations",
    "type": "base",
    "fields": [
      {
        "name": "user",
        "type": "relation",
        "required": true,
        "options": {
          "collectionId": "<USERS_COLLECTION_ID>",
          "cascadeDelete": false,
          "maxSelect": 1
        }
      },
      { "name": "bookId", "type": "text", "required": true },
      { "name": "locatorJson", "type": "text", "required": true },
      { "name": "selectedText", "type": "text" },
      { "name": "note", "type": "text" },
      { "name": "style", "type": "text" },
      { "name": "createdAt", "type": "number" },
      { "name": "updatedAt", "type": "number" }
    ],
    "listRule": "@request.auth.id != \"\" && user = @request.auth.id",
    "viewRule": "@request.auth.id != \"\" && user = @request.auth.id",
    "createRule": "@request.auth.id != \"\" && user = @request.auth.id",
    "updateRule": "@request.auth.id != \"\" && user = @request.auth.id",
    "deleteRule": "@request.auth.id != \"\" && user = @request.auth.id"
  }
]
```

---

## 驗證

**1. 確認 collection 存在、且規則生效**

未登入時，PocketBase 會把 list rule 當成過濾條件套用，因此**回應是 200 加上空的清單**
（不是 401）。要判定的重點是 `totalItems` 是否為 0：

```bash
curl -s https://<你的網域>/api/collections/annotations/records
# 期望： {"items":[],"page":1,"perPage":30,"totalItems":0,"totalPages":0}
#        totalItems > 0 而未登入 → 規則沒設好（資料對外洩漏）
#        404 → collection 還沒建立
```

最可靠的比對方式：拿一個已經設好規則的 collection（例如 `bookmarks`）對照，兩者回應應該一模一樣：

```bash
for c in bookmarks annotations; do
  printf '%-12s ' "$c"
  curl -s "https://<你的網域>/api/collections/$c/records" | head -c 80; echo
done
```

另外，未登入讀取單筆（view rule 失敗）應回 **404**：

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  https://<你的網域>/api/collections/annotations/records/doesnotexist
# 期望：404
```

**2. 在 App 上驗證**

1. 裝置 A：在書上畫一條線 → 等同步（或到主畫面手動完整同步）
2. 管理介面 → **annotations** → 應該看到一筆記錄（`user` 指向你的帳號）
3. 裝置 B：執行完整同步 → 應該看到同一條畫線

**3. 若沒出現**

| 症狀 | 檢查 |
| --- | --- |
| 完全沒有記錄 | 裝置是否已登入？App 的 `POCKETBASE_URL` 是否指向同一個伺服器？ |
| 有記錄但其他裝置看不到 | 拉取端的 `user` 是否相同帳號（`user = @request.auth.id` 規則會過濾） |
| 未登入卻看得到別人的畫線（`totalItems > 0`）| 五條 API rules 是否都填了 `@request.auth.id != "" && user = @request.auth.id` |
| 已登入卻拉不到資料（401/403）| 同上；並確認 `user` 是 relation 指向 `users` 且該筆記錄的 `user` 就是你自己的帳號 |
| 只有本機有 | `AnnotationRepository.sync()` 未被呼叫 —— 完整同步在 `MainActivity.executeFullSync` 內應已包含畫線 |

---

## 相關程式碼

| 檔案 | 角色 |
| --- | --- |
| `shared/.../data/repo/AnnotationSync.kt` | 推送（POST/PATCH）、拉取、刪除 |
| `shared/.../data/repo/AnnotationRepository.kt` | 本機存取 + 呼叫同步（`syncRepo` 為 null 時維持純本機） |
| `shared/.../data/repo/UserSyncRepository.kt` | `pullAnnotations` / `pushAnnotation` / `deleteAnnotation` delegate |
| `shared/.../data/db/AnnotationEntity.kt` | `remoteId` / `isSynced` 欄位 |
| `app/src/test/.../AnnotationSyncTest.kt` | 7 個測試（含離線路徑） |
| `scripts/add_annotations_collection.py` | 幂等的自動建立腳本（只碰 `annotations`） |

> ⚠️ 不要直接執行 `setup_pocketbase.py`：它會對**已存在**的同名 collection 發 PATCH，
> 把欄位與規則覆蓋成 schema 檔內容，而其預設 schema 檔是舊版快照。
> 若真的要用，請先 `--pull-schema` 抓下現況再套用。
