#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
./gradlew --offline -PvocaeaseApiBaseUrl=https://api.example/ :app:validateReleaseApiBaseUrl >"$log" 2>&1 || { cat "$log"; exit 1; }
for invalid in https://api.example/api/ https://api.example/api/v1/; do
  if ./gradlew --offline -PvocaeaseApiBaseUrl="$invalid" :app:validateReleaseApiBaseUrl >"$log" 2>&1; then
    echo "错误：发布校验接受了会重复拼接接口路径的地址：$invalid" >&2
    exit 1
  fi
  rg -q 'release API 地址' "$log" || { cat "$log"; exit 1; }
done
echo "发布API根地址回归通过"
