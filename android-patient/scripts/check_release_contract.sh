#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
missing_url_log="$(mktemp /tmp/vocaease-release-missing.XXXXXX)"
invalid_url_log="$(mktemp /tmp/vocaease-release-invalid.XXXXXX)"
trap 'rm -f "$missing_url_log" "$invalid_url_log"' EXIT

if env -u VOCAEASE_API_BASE_URL \
  "$project_dir/gradlew" -p "$project_dir" \
    -PvocaeaseApiBaseUrl= \
    :app:assemble >"$missing_url_log" 2>&1; then
  echo "未提供 release API 地址时，聚合 assemble 不应成功" >&2
  exit 1
fi

if ! rg -q 'release 构建必须通过' "$missing_url_log"; then
  echo "聚合 assemble 虽然失败，但不是 release API 地址门禁触发" >&2
  cat "$missing_url_log" >&2
  exit 1
fi

if "$project_dir/gradlew" -p "$project_dir" \
  -PvocaeaseApiBaseUrl=http://127.0.0.1/ \
  :app:assemble >"$invalid_url_log" 2>&1; then
  echo "release API 地址为 HTTP 时，聚合 assemble 不应成功" >&2
  exit 1
fi

if ! rg -q 'release API 地址必须' "$invalid_url_log"; then
  echo "HTTP 地址虽被拒绝，但不是 release URL 格式门禁触发" >&2
  cat "$invalid_url_log" >&2
  exit 1
fi

release_url="${VOCAEASE_API_BASE_URL:-https://127.0.0.1/}"
"$project_dir/gradlew" -p "$project_dir" \
  -PvocaeaseApiBaseUrl="$release_url" \
  :app:assemble

echo "release 聚合构建契约通过"
