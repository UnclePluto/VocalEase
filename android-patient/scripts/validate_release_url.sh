#!/usr/bin/env bash
set -euo pipefail

release_url="${VOCAEASE_RELEASE_API_BASE_URL:-}"

if [[ -z "$release_url" ]]; then
  echo "release 构建必须通过 -PvocaeaseApiBaseUrl 或 VOCAEASE_API_BASE_URL 提供 API 地址" >&2
  exit 1
fi

if [[ "$release_url" != https://* || "$release_url" != */ ]]; then
  echo "release API 地址必须是包含主机且以 / 结尾的完整 HTTPS URL" >&2
  exit 1
fi

authority_and_path="${release_url#https://}"
authority="${authority_and_path%%/*}"
if [[ -z "$authority" || "$authority" == *[[:space:]]* ]]; then
  echo "release API 地址必须是包含主机且以 / 结尾的完整 HTTPS URL" >&2
  exit 1
fi
