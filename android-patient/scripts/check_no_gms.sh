#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report_file="$project_dir/app/build/reports/release-runtime-dependencies.txt"

: "${VOCAEASE_API_BASE_URL:?请设置 VOCAEASE_API_BASE_URL（完整 HTTPS URL，且以 / 结尾）}"

mkdir -p "$(dirname "$report_file")"
"$project_dir/gradlew" \
  -p "$project_dir" \
  --offline \
  --dependency-verification strict \
  -PvocaeaseApiBaseUrl="$VOCAEASE_API_BASE_URL" \
  :app:dependencies \
  --configuration releaseRuntimeClasspath \
  > "$report_file"

if rg -i 'com\.google\.android\.gms|com\.google\.firebase|play-services|firebase-' "$report_file"; then
  echo "检测到禁止使用的 GMS/Firebase 依赖" >&2
  exit 1
fi

echo "无 GMS 审计通过：$report_file"
