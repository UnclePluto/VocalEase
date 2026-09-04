#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
validator="$project_dir/scripts/validate_network_security_files.sh"
test_dir="$(mktemp -d /tmp/vocaease-network-security-test.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT

"$project_dir/scripts/check_debug_network_security.sh" >/dev/null

debug_manifest="$project_dir/app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
release_manifest="$project_dir/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"
network_config="$project_dir/app/src/debug/res/xml/network_security_config.xml"

if "$validator" "$test_dir/missing-debug.xml" "$release_manifest" "$network_config" >/dev/null 2>&1; then
  echo "缺失清单必须使网络安全校验失败" >&2
  exit 1
fi

cp "$release_manifest" "$test_dir/release-injected.xml"
printf '\nandroid:networkSecurityConfig="@xml/network_security_config"\n' >> "$test_dir/release-injected.xml"
if "$validator" "$debug_manifest" "$test_dir/release-injected.xml" "$network_config" >/dev/null 2>&1; then
  echo "release 注入 networkSecurityConfig 必须使校验失败" >&2
  exit 1
fi

sed 's#</domain-config>#<domain includeSubdomains="false">evil.example</domain></domain-config>#' \
  "$network_config" > "$test_dir/network-injected.xml"
if "$validator" "$debug_manifest" "$release_manifest" "$test_dir/network-injected.xml" >/dev/null 2>&1; then
  echo "debug 注入额外明文域必须使校验失败" >&2
  exit 1
fi

echo "网络安全脚本负例通过"
