#!/bin/bash
set -Eeuo pipefail
echo "Simulating fallback..."
# If there are no other changed files, grep -v outputs nothing and exits 1
CHANGED_FILES=$(echo "app/build.gradle.kts" | grep -v "build.gradle.kts" | head -n 3 | xargs)
echo "This will never print"
