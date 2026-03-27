#!/bin/bash
set -eo pipefail
val=$(echo "a" | grep "b" | wc -l || true)
echo "val=$val"
