#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
merged_manifest="$project_dir/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
debug_apk="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
apkanalyzer="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"

"$project_dir/gradlew" -p "$project_dir" :app:assembleDebug >/dev/null

expected_permissions=$'android.permission.ACCESS_NETWORK_STATE\nandroid.permission.CAMERA\nandroid.permission.FOREGROUND_SERVICE\nandroid.permission.FOREGROUND_SERVICE_DATA_SYNC\nandroid.permission.INTERNET\nandroid.permission.POST_NOTIFICATIONS\nandroid.permission.RECORD_AUDIO\nandroid.permission.WAKE_LOCK'
merged_permissions="$(
  rg -o 'android:name="android\.permission\.[A-Z_]+"' "$merged_manifest" \
    | cut -d'"' -f2 \
    | sort -u
)"
apk_permissions="$(
  "$apkanalyzer" manifest permissions "$debug_apk" \
    | rg '^android\.permission\.[A-Z_]+$' \
    | sort -u
)"

if ! diff -u <(printf '%s\n' "$expected_permissions") <(printf '%s\n' "$merged_permissions"); then
  echo "合并清单包含计划外权限或缺少既定权限" >&2
  exit 1
fi

if ! diff -u <(printf '%s\n' "$expected_permissions") <(printf '%s\n' "$apk_permissions"); then
  echo "APK 包含计划外权限或缺少既定权限" >&2
  exit 1
fi

echo "合并清单与 APK 权限契约通过"
