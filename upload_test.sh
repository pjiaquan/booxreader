#!/bin/bash

# --- 配置區 ---
BASE_URL="https://app.hinoki.my"
ANON_KEY="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzY2MDczNjAwLCJleHAiOjE5MjM4NDAwMDB9.JFC5hdPzUBYTxiEIYv4wBgQdxxtgL941HOB6YAa32Is"
USER_EMAIL="support@hinoki.my"
USER_PASSWORD="12345678Aa!"
BUCKET_NAME="books"
FILE_NAME="test.txt"

echo "Hello from authenticated user at $(date)" > "$FILE_NAME"

# 檢查檔案是否真的建立了
if [ ! -f "$FILE_NAME" ]; then
    echo "❌ 錯誤：無法建立測試檔案 $FILE_NAME，請檢查資料夾權限。"
    exit 1
fi

echo "--- 正在嘗試登入 ---"

# 2. 登入並獲取 Token
# Supabase GoTrue API 登入路徑: /auth/v1/token?grant_type=password
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/v1/token?grant_type=password" \
  -H "apikey: $ANON_KEY" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$USER_EMAIL\",
    \"password\": \"$USER_PASSWORD\"
  }")

# 使用 jq 解析 Access Token
USER_TOKEN=$(echo $LOGIN_RESPONSE | jq -r '.access_token')

if [ "$USER_TOKEN" == "null" ] || [ -z "$USER_TOKEN" ]; then
  echo "登入失敗！請檢查帳號密碼或 ANON_KEY。"
  echo "API 回傳內容: $LOGIN_RESPONSE"
  exit 1
fi

echo "登入成功，已取得 Token。"

echo "--- 正在上傳檔案到 R2 (透過 Supabase Storage API, authenticated) ---"

# 3. 使用用戶 Token 上傳檔案
UPLOAD_RESPONSE=$(curl -s -X POST "$BASE_URL/storage/v1/object/authenticated/$BUCKET_NAME/$FILE_NAME" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "apikey: $ANON_KEY" \
  -H "Content-Type: text/plain" \
  --data-binary "@$FILE_NAME")

echo "伺服器回傳: $UPLOAD_RESPONSE"

# 檢查結果
if echo $UPLOAD_RESPONSE | grep -q "Id"; then
  echo "✅ authenticated 上傳成功！檔案已存入 R2。"
else
  echo "❌ authenticated 上傳失敗。請檢查該 Bucket 的 RLS 權限設定。"
fi

echo "--- 正在上傳檔案到 R2 (透過 Supabase Storage API, public) ---"
PUBLIC_UPLOAD_RESPONSE=$(curl -s -X POST "$BASE_URL/storage/v1/object/public/$BUCKET_NAME/$FILE_NAME" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "apikey: $ANON_KEY" \
  -H "Content-Type: text/plain" \
  --data-binary "@$FILE_NAME")

echo "伺服器回傳: $PUBLIC_UPLOAD_RESPONSE"

if echo $PUBLIC_UPLOAD_RESPONSE | grep -q "Id"; then
  echo "✅ public 上傳成功！檔案已存入 R2。"
else
  echo "❌ public 上傳失敗。請檢查該 Bucket 是否為 public。"
fi

echo "--- 正在上傳檔案到 R2 (透過 Supabase Storage API, no access mode) ---"
PLAIN_UPLOAD_RESPONSE=$(curl -s -X POST "$BASE_URL/storage/v1/object/$BUCKET_NAME/$FILE_NAME" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "apikey: $ANON_KEY" \
  -H "Content-Type: text/plain" \
  --data-binary "@$FILE_NAME")

echo "伺服器回傳: $PLAIN_UPLOAD_RESPONSE"

if echo $PLAIN_UPLOAD_RESPONSE | grep -q "Id"; then
  echo "✅ no-mode 上傳成功！檔案已存入 R2。"
else
  echo "❌ no-mode 上傳失敗。"
fi
