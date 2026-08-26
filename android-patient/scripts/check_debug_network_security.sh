#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
debug_manifest="$project_dir/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
release_manifest="$project_dir/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
network_config="$project_dir/app/src/debug/res/xml/network_security_config.xml"

release_url="${VOCAEASE_API_BASE_URL:-https://127.0.0.1/}"
"$project_dir/gradlew" -p "$project_dir" \
    -PvocaeaseApiBaseUrl="$release_url" \
    :app:processDebugMainManifest \
    :app:processReleaseMainManifest >/dev/null

if ! rg -q 'android:networkSecurityConfig="@xml/network_security_config"' "$debug_manifest"; then
  echo "debug 合并清单未启用专用网络安全配置" >&2
  exit 1
fi

if rg -q 'android:networkSecurityConfig' "$release_manifest"; then
  echo "release 合并清单不应放宽明文网络策略" >&2
  exit 1
fi

test "$(xmllint --xpath 'string(/network-security-config/base-config/@cleartextTrafficPermitted)' "$network_config")" = "false"
test "$(xmllint --xpath 'count(/network-security-config/domain-config)' "$network_config")" = "1"
test "$(xmllint --xpath 'string(/network-security-config/domain-config/@cleartextTrafficPermitted)' "$network_config")" = "true"
test "$(xmllint --xpath 'normalize-space(/network-security-config/domain-config/domain)' "$network_config")" = "10.0.2.2"
test "$(xmllint --xpath 'string(/network-security-config/domain-config/domain/@includeSubdomains)' "$network_config")" = "false"

echo "debug 网络安全契约通过"
