#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scanner="$project_dir/scripts/scan_release_privacy.sh"
fixtures="$project_dir/scripts/fixtures/privacy-scanner"
temp_root="$(mktemp -d "${TMPDIR:-/tmp}/vocaease-privacy-scanner.XXXXXX")"
trap 'rm -rf "$temp_root"' EXIT

command -v zip >/dev/null 2>&1 || { echo "缺少测试工具：zip" >&2; exit 2; }
[[ -x "$scanner" ]] || { echo "隐私扫描器不存在或不可执行" >&2; exit 1; }

make_apk() {
  local fixture_dir="$1"
  local destination="$2"
  (
    cd "$fixture_dir"
    zip -q "$destination" ./*
  )
}

make_apk "$fixtures/safe" "$temp_root/safe.apk"
"$scanner" "$fixtures/safe" "$temp_root/safe.apk"

assert_rejected() {
  local label="$1"
  local source_root="$2"
  local apk="$3"
  set +e
  "$scanner" "$source_root" "$apk" >/dev/null 2>&1
  local status="$?"
  set -e
  if [[ "$status" -ne 1 ]]; then
    echo "负例未被拒绝：$label" >&2
    exit 1
  fi
}

assert_rejected "java.util.logging" "$fixtures/bad-logger" "$temp_root/safe.apk"
assert_rejected "Kotlin Log import alias" "$fixtures/bad-kotlin-log-alias" "$temp_root/safe.apk"
assert_rejected "Kotlin println import alias" "$fixtures/bad-println-alias" "$temp_root/safe.apk"
assert_rejected "Kotlin println 方法引用" "$fixtures/bad-println-reference" "$temp_root/safe.apk"
assert_rejected "allowlist 文件内 Logger 变量别名" "$fixtures/bad-allowed-logger-alias" "$temp_root/safe.apk"
assert_rejected "Java Logger" "$fixtures/bad-java-logger" "$temp_root/safe.apk"
assert_rejected "间接 WorkData" "$fixtures/bad-workdata" "$temp_root/safe.apk"
assert_rejected "Kotlin WorkData import alias" "$fixtures/bad-workdata-alias" "$temp_root/safe.apk"
assert_rejected "allowlist 文件内 WorkData 方法引用" "$fixtures/bad-workdata-method-reference" "$temp_root/safe.apk"
assert_rejected "allowlist 文件内 WorkData 扩展方法" "$fixtures/bad-workdata-extension" "$temp_root/safe.apk"
assert_rejected "Java WorkData 变量别名" "$fixtures/bad-java-workdata" "$temp_root/safe.apk"
make_apk "$fixtures/bad-apk" "$temp_root/bad-secret.apk"
assert_rejected "APK Bearer" "$fixtures/safe" "$temp_root/bad-secret.apk"
for apk_case in jwt password-json signed-url object-key absolute-path; do
  make_apk "$fixtures/bad-apk-$apk_case" "$temp_root/bad-$apk_case.apk"
  assert_rejected "APK $apk_case" "$fixtures/safe" "$temp_root/bad-$apk_case.apk"
done

echo "隐私扫描器正例与 17 类稳定负例通过"
