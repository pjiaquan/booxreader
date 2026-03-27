#!/bin/bash
set -Eeuo pipefail
# emulate the STAGED_DIFF construction
STAGED_DIFF="fake diff\n"
diff_content="$STAGED_DIFF"
extra_system=""
system_content="You are a senior software engineer."
user_content="Generate a descriptive commit message for these changes:\n"

escaped_diff=$(echo "$diff_content" | jq -sRr @json)

json_body=$(jq -n \
              --arg sys "$system_content" \
              --arg usr "$user_content" \
              --arg diff "$escaped_diff" \
              --arg model "groq/compound" \
              '{
                model: $model,
                messages: [
                  {role: "system", content: $sys},
                  {role: "user", content: ($usr + ($diff | fromjson))}
                ]
              }')
echo "Success: $json_body"
