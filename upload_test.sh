#!/bin/bash

# --- 配置區 ---
BASE_URL="https://app.hinoki.my"
ANON_KEY="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzY2MDczNjAwLCJleHAiOjE5MjM4NDAwMDB9.JFC5hdPzUBYTxiEIYv4wBgQdxxtgL941HOB6YAa32Is"
USER_EMAIL="support@hinoki.my"
USER_PASSWORD="12345678Aa!"

#BASE_URL="http://localhost:8000"
#ANON_KEY="${ANON_KEY:-your_anon_key_here}" 

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


echo "--- 3. 檢查初始額度 (GET /billing/status) ---"
CREDITS_RESPONSE=$(curl -s -X GET "$BASE_URL/functions/v1/billing/status" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json")

echo "Billing stauts: $CREDITS_RESPONSE"


