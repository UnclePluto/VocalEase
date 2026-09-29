#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
log_dir="$(mktemp -d /tmp/vocaease-release-contract.XXXXXX)"
trap 'rm -rf "$log_dir"' EXIT

expect_rejected() {
  local label="$1"
  local task_name="$2"
  local release_url="$3"
  local expected_message="$4"
  local log_file="$log_dir/$label.log"

  if env -u VOCAEASE_API_BASE_URL \
    "$project_dir/gradlew" -p "$project_dir" \
      -PvocaeaseApiBaseUrl="$release_url" \
      "$task_name" >"$log_file" 2>&1; then
    echo "${label}：恶意或缺失 release API 地址不应构建成功" >&2
    exit 1
  fi

  if ! rg -q "$expected_message" "$log_file"; then
    echo "${label}：失败原因不是 release URL 门禁" >&2
    cat "$log_file" >&2
    exit 1
  fi
  echo "${label}：已拒绝"
}

expect_accepted() {
  local label="$1"
  local task_name="$2"
  local release_url="$3"

  "$project_dir/gradlew" -p "$project_dir" \
    -PvocaeaseApiBaseUrl="$release_url" \
    "$task_name" >/dev/null
  echo "${label}：通过"
}

expect_rejected "空地址" ":app:assemble" "" "release 构建必须通过"
expect_rejected "HTTP 地址" ":app:validateReleaseApiBaseUrl" "http://127.0.0.1/" "release API 地址必须"
expect_rejected "缺少尾斜线" ":app:validateReleaseApiBaseUrl" "https://127.0.0.1" "release API 地址必须"
expect_rejected "端口但无主机" ":app:assembleRelease" "https://:443/" "release API 地址必须"
expect_rejected "仅 userinfo" ":app:validateReleaseApiBaseUrl" "https://user@/" "release API 地址必须"
expect_rejected "缺少 authority" ":app:validateReleaseApiBaseUrl" "https:///api/" "release API 地址必须"

expect_accepted "IPv4 地址" ":app:assemble" "https://127.0.0.1/"
expect_accepted "DNS 地址" ":app:validateReleaseApiBaseUrl" "https://api.vocaease.example/"
expect_accepted "IPv6 地址" ":app:validateReleaseApiBaseUrl" "https://[::1]/"

echo "release 聚合构建契约通过"
