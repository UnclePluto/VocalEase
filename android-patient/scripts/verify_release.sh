#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 0 ]]; then
  echo "用法：VOCAEASE_API_BASE_URL=https://api.example/ ./scripts/verify_release.sh" >&2
  exit 2
fi

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

for tool in awk diff find mktemp rg sed sort strings tail tr unzip xmllint zip; do
  command -v "$tool" >/dev/null 2>&1 || { echo "缺少必需工具：$tool" >&2; exit 2; }
done
[[ -x "$project_dir/gradlew" ]] || { echo "Gradle wrapper 不可执行" >&2; exit 2; }
: "${ANDROID_HOME:?请设置 ANDROID_HOME}"
: "${JAVA_HOME:?请设置 JAVA_HOME 并指向 JDK 17}"
[[ -x "$JAVA_HOME/bin/java" ]] || { echo "JAVA_HOME/bin/java 不可执行" >&2; exit 2; }
java_specification="$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | awk -F= '/java.specification.version/ { gsub(/[[:space:]]/, "", $2); print $2; exit }')"
[[ "$java_specification" == "17" ]] || { echo "必须使用 JDK 17" >&2; exit 2; }
java_home_real="$(cd "$JAVA_HOME" && pwd -P)"
gradle_runtime="$(./gradlew --offline --version 2>&1)"
launcher_jvm="$(awk -F: '/^Launcher JVM:/ { sub(/^[[:space:]]*/, "", $2); print $2; exit }' <<< "$gradle_runtime")"
daemon_jvm="$(awk -F':    ' '/^Daemon JVM:/ { sub(/ \(.*/, "", $2); print $2; exit }' <<< "$gradle_runtime")"
[[ "$launcher_jvm" == 17.* ]] || { echo "Gradle Launcher JVM 不是 17" >&2; exit 2; }
[[ -d "$daemon_jvm" && "$(cd "$daemon_jvm" && pwd -P)" == "$java_home_real" ]] || {
  echo "Gradle Daemon JVM 与 JAVA_HOME 不一致" >&2
  exit 2
}
for platform in 29 37; do
  if [[ ! -f "$ANDROID_HOME/platforms/android-$platform/android.jar" &&
        ! -f "$ANDROID_HOME/platforms/android-$platform.0/android.jar" ]]; then
    echo "缺少 Android SDK platform $platform" >&2
    exit 2
  fi
done
apkanalyzer="$(command -v apkanalyzer || true)"
if [[ -z "$apkanalyzer" ]]; then
  apkanalyzer="$(find "$ANDROID_HOME/cmdline-tools" -type f -path '*/bin/apkanalyzer' -perm -111 2>/dev/null |
    sort -V | tail -1)"
fi
[[ -n "$apkanalyzer" && -x "$apkanalyzer" ]] || { echo "找不到 PATH 或 Android SDK cmdline-tools 中的 apkanalyzer" >&2; exit 2; }
: "${VOCAEASE_API_BASE_URL:?请设置 VOCAEASE_API_BASE_URL（完整 HTTPS URL，且以 / 结尾）}"

# 与 release lifecycle 共用 buildSrc 的 java.net.URI 校验，避免 shell 与构建规则漂移。
./gradlew --offline -PvocaeaseApiBaseUrl="$VOCAEASE_API_BASE_URL" :app:validateReleaseApiBaseUrl >/dev/null

echo "[1/7] 离线 JVM、release lint 与 APK 构建"
gradle_common=(--offline --dependency-verification strict -PvocaeaseApiBaseUrl="$VOCAEASE_API_BASE_URL")
./gradlew "${gradle_common[@]}" :app:testDebugUnitTest
./gradlew "${gradle_common[@]}" :app:lintRelease
./gradlew "${gradle_common[@]}" :app:assembleRelease

apk="app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -f "$apk" ]] || { echo "release APK 不存在" >&2; exit 1; }

echo "[2/7] 无 GMS/Firebase 依赖"
./scripts/check_no_gms.sh

echo "[3/7] release 权限精确集合"
expected_permissions=$'android.permission.ACCESS_NETWORK_STATE\nandroid.permission.CAMERA\nandroid.permission.FOREGROUND_SERVICE\nandroid.permission.FOREGROUND_SERVICE_DATA_SYNC\nandroid.permission.INTERNET\nandroid.permission.POST_NOTIFICATIONS\nandroid.permission.RECORD_AUDIO\nandroid.permission.WAKE_LOCK'
actual_permissions="$("$apkanalyzer" manifest permissions "$apk" | rg '^android\.permission\.[A-Z_]+$' | sort -u)"
if ! diff -u <(printf '%s\n' "$expected_permissions") <(printf '%s\n' "$actual_permissions"); then
  echo "release APK 包含计划外权限或缺少既定权限" >&2
  exit 1
fi

echo "[4/7] debug/release 网络策略"
debug_manifest="app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
release_manifest="app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"
./gradlew "${gradle_common[@]}" :app:processDebugMainManifest :app:processReleaseMainManifest >/dev/null
./scripts/validate_network_security_files.sh \
  "$debug_manifest" "$release_manifest" app/src/debug/res/xml/network_security_config.xml

echo "[5/7] Room schema 与患者文案静态审计"
latest_schema="$(find app/schemas/com.vocaease.patient.core.database.VocaEaseDatabase -type f -name '*.json' | sort -V | tail -1)"
[[ -n "$latest_schema" && -f "$latest_schema" ]] || { echo "找不到 Room schema" >&2; exit 1; }
if rg -n -i 'private[_ ]?url|upload[_ ]?token|refresh[_ ]?token|plaintext[_ ]?path|absolute[_ ]?path' "$latest_schema"; then
  echo "Room schema 包含禁止持久化的敏感字段" >&2
  exit 1
fi
if rg -n '模拟分析|非临床结论' app/src/main; then
  echo "患者端包含禁止文案" >&2
  exit 1
fi

echo "[6/7] 隐私扫描器稳定正例与负例"
./scripts/test_privacy_scanner.sh

echo "[7/7] release 源码、DEX、resources 与 native 隐私扫描"
./scripts/scan_release_privacy.sh app/src/main/java "$apk"

python3 ./scripts/check_release_response_types.py "$apk" app/build/outputs/mapping/release/mapping.txt

echo "release 验证通过（API 主机与凭据未输出）"
