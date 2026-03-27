#!/bin/bash
set -Eeuo pipefail
IFS=$'\n\t'
secret_scan_patterns=(
    'AKIA[0-9A-Z]{16}'
    '-----BEGIN (RSA|DSA|EC|OPENSSH|PRIVATE) KEY-----'
)
cmd="grep -Eni $(printf -- "-e %q " "${secret_scan_patterns[@]}")"
echo "Command is: $cmd"
grep -Eni $(printf -- "-e %q " "${secret_scan_patterns[@]}") << 'INNER' || true
hello
INNER
echo "Done"
