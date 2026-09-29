#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 3 ]]; then
  echo "用法：validate_network_security_files.sh DEBUG_MANIFEST RELEASE_MANIFEST NETWORK_CONFIG" >&2
  exit 2
fi

debug_manifest="$1"
release_manifest="$2"
network_config="$3"

for required_file in "$debug_manifest" "$release_manifest" "$network_config"; do
  if [[ ! -f "$required_file" ]]; then
    echo "网络安全校验目标不存在：$required_file" >&2
    exit 1
  fi
done

if ! rg -q 'android:networkSecurityConfig="@xml/network_security_config"' "$debug_manifest"; then
  echo "debug 合并清单未启用专用网络安全配置" >&2
  exit 1
fi

if rg -q 'android:usesCleartextTraffic="true"' "$debug_manifest"; then
  echo "debug 合并清单不得全局允许明文流量" >&2
  exit 1
fi

if rg -q 'android:networkSecurityConfig|android:usesCleartextTraffic="true"' "$release_manifest"; then
  echo "release 合并清单不应放宽明文网络策略" >&2
  exit 1
fi

if [[ "$(xmllint --xpath 'string(/network-security-config/base-config/@cleartextTrafficPermitted)' "$network_config")" != "false" ]]; then
  echo "debug 基础网络策略必须禁止明文" >&2
  exit 1
fi

if [[ "$(xmllint --xpath 'count(/network-security-config/domain-config)' "$network_config")" != "1" ]]; then
  echo "debug 只能存在一个明文域配置" >&2
  exit 1
fi

if [[ "$(xmllint --xpath 'count(/network-security-config/domain-config/domain)' "$network_config")" != "2" ]]; then
  echo "debug 明文域配置必须且只能包含两个环回域名" >&2
  exit 1
fi

if [[ "$(xmllint --xpath 'string(/network-security-config/domain-config/@cleartextTrafficPermitted)' "$network_config")" != "true" ]]; then
  echo "debug 模拟器域必须明确允许明文" >&2
  exit 1
fi

if [[ "$(xmllint --xpath 'normalize-space(/network-security-config/domain-config/domain[1])' "$network_config")" != "10.0.2.2" ]] ||
   [[ "$(xmllint --xpath 'normalize-space(/network-security-config/domain-config/domain[2])' "$network_config")" != "localhost" ]]; then
  echo "debug 明文域必须且只能依次是 10.0.2.2 与 localhost" >&2
  exit 1
fi

if [[ "$(xmllint --xpath 'count(/network-security-config/domain-config/domain[@includeSubdomains="false"])' "$network_config")" != "2" ]]; then
  echo "debug 明文域不得包含子域" >&2
  exit 1
fi
