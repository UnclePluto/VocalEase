#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
debug_manifest="$project_dir/app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
release_manifest="$project_dir/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"
network_config="$project_dir/app/src/debug/res/xml/network_security_config.xml"
validator="$project_dir/scripts/validate_network_security_files.sh"

release_url="${VOCAEASE_API_BASE_URL:-https://127.0.0.1/}"
"$project_dir/gradlew" -p "$project_dir" \
    -PvocaeaseApiBaseUrl="$release_url" \
    :app:processDebugMainManifest \
    :app:processReleaseMainManifest >/dev/null

"$validator" "$debug_manifest" "$release_manifest" "$network_config"

echo "debug 网络安全契约通过"
