#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "用法：scan_release_privacy.sh <生产源码目录> <release APK>" >&2
  exit 2
fi

source_root="$1"
apk="$2"
[[ -d "$source_root" ]] || { echo "生产源码目录不存在" >&2; exit 2; }
[[ -f "$apk" ]] || { echo "release APK 不存在" >&2; exit 2; }
for tool in diff find rg sort strings unzip; do
  command -v "$tool" >/dev/null 2>&1 || { echo "缺少隐私扫描工具：$tool" >&2; exit 2; }
done

fail() {
  echo "隐私扫描失败：$1" >&2
  exit 1
}

# 日志 allowlist 只允许 NetworkModule 的脱敏诊断出口。同时扫 Kotlin/Java 的 import alias、
# fully-qualified 引用和直接 stdout，避免只匹配 `Log.e` 调用形态而被别名绕过。
if rg -q 'android\.util\.Log\b|timber\.log\.Timber\b|kotlin\.io\.(println|print)\b|::[[:space:]]*(println|print)\b|(^|[^A-Za-z])Timber\.|println\(|(^|[^A-Za-z])print\(|System\.(out|err)\.' \
  "$source_root" --glob '*.kt' --glob '*.java'; then
  fail "存在未经 allowlist 审计的直接日志调用"
fi

logger_files="$(rg -l 'java\.util\.logging|Logger\.getLogger|logger\.(log|severe|warning|info|config|fine|finer|finest)\(' \
  "$source_root" --glob '*.kt' --glob '*.java' | sort || true)"
if [[ -n "$logger_files" ]]; then
  while IFS= read -r logger_file; do
    relative="${logger_file#"$source_root"/}"
    [[ "$relative" == "com/vocaease/patient/core/network/NetworkModule.kt" ]] ||
      fail "Logger 仅允许出现在 NetworkModule"
    # 比对所有 Logger/logger token 行，而不是只比对旧调用模式。因此 `val alias = logger`、
    # import alias 或第二个 sink 都会改变 allowlist 指纹并 fail closed。
    logger_calls="$(rg '\b(Logger|logger)\b' \
      "$logger_file" | sed 's/[[:blank:]]//g' | sort)"
    expected_logger_calls=$'importjava.util.logging.Logger\nlogger.info(diagnostic.toLogLine())\nprivatevallogger=Logger.getLogger("VocaEaseNetwork")'
    [[ "$logger_calls" == "$expected_logger_calls" ]] || fail "NetworkModule Logger 调用偏离脱敏 allowlist"
  done <<< "$logger_files"
fi

# WorkManager Data 采用逐文件、逐表达式 allowlist，间接变量和新增 Data 构造都会被拒绝。
work_files="$(rg -l 'androidx\.work\.(Data|workDataOf)|Data\.Builder|workDataOf\(' \
  "$source_root" --glob '*.kt' --glob '*.java' | sort || true)"
if [[ -n "$work_files" ]]; then
  while IFS= read -r work_file; do
    relative="${work_file#"$source_root"/}"
    case "$relative" in
      com/vocaease/patient/core/cleanup/DailyDraftCleanup.kt)
        expected=$'.setInputData(Data.Builder().putString(SCOPE_HASH_KEY,scopeHash).putString(SCOPE_TOKEN_KEY,scopeToken).build())\nimportandroidx.work.Data'
        ;;
      com/vocaease/patient/core/security/RevocationWorkContract.kt)
        expected=$'.setInputData(Data.Builder().putString(SLOT_ID_KEY,handle.slotId).build())\nimportandroidx.work.Data'
        ;;
      com/vocaease/patient/feature/history/AnalysisWorkContract.kt)
        expected=$'.putString(ACCOUNT_SCOPE_HASH_KEY,accountScopeHash)\n.putString(INCARNATION_PROOF_KEY,incarnationProof)\n.putString(SESSION_ID_KEY,sessionId)\nData.Builder()\nimportandroidx.work.Data'
        ;;
      com/vocaease/patient/feature/upload/UploadWorkContract.kt)
        expected=$'.putString(ACCOUNT_SCOPE_HASH_KEY,accountScopeHash)\n.putString(DRAFT_ID_KEY,draftId)\nData.Builder()\nimportandroidx.work.Data'
        ;;
      com/vocaease/patient/feature/upload/UploadWorker.kt)
        expected='setProgressAsync(androidx.work.workDataOf(PROGRESS_KEYtosafePercent))'
        ;;
      *) fail "发现未列入 allowlist 的 WorkData 构造文件" ;;
    esac
    actual="$(rg 'androidx\.work\.(Data|workDataOf)\b|\bData\.Builder\b|\bput(Boolean|Byte|ByteArray|Double|Float|Int|Long|String|BooleanArray|DoubleArray|IntArray|LongArray|StringArray)\b|\bworkDataOf\b' "$work_file" |
      sed 's/[[:blank:]]//g' | sort)"
    [[ "$actual" == "$expected" ]] || fail "WorkData/Data.Builder token 行偏离完整 allowlist 指纹"
  done <<< "$work_files"
fi
if rg -q '(^|[^A-Za-z0-9_.])Data[[:space:]]*\(' "$source_root" --glob '*.kt' --glob '*.java'; then
  fail "禁止直接 Data(...) 构造"
fi
if rg -q -i '(password|access.?token|refresh.?token|upload.?token|private.?url|object.?key)[A-Za-z0-9_[:space:]]*=[[:space:]]*"[^"]+"' \
  "$source_root" --glob '*.kt' --glob '*.java'; then
  fail "生产源码包含疑似硬编码密码、令牌、私有 URL 或对象键"
fi

extract_root="$(mktemp -d "${TMPDIR:-/tmp}/vocaease-apk-scan.XXXXXX")"
strings_output="$(mktemp "${TMPDIR:-/tmp}/vocaease-apk-strings.XXXXXX")"
trap 'rm -rf "$extract_root"; rm -f "$strings_output"' EXIT
unzip -oq "$apk" -d "$extract_root"

# 对 DEX、resources、native 库及其它 APK 条目统一跑 strings；匹配内容永不回显。
secret_pattern="(eyJ[A-Za-z0-9_-]{7,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}|Bearer[[:space:]]+[A-Za-z0-9._~+/-]{8,}|(upload|refresh|access)[_-]secret|\"(password|old_password|new_password|refresh_token|access_token|upload_token)\"[[:space:]]*:[[:space:]]*\"[^\"]+\"|https?://[^[:space:]\"]*[?&](token|e|sign|auth)=|private/(session|patient)/[A-Za-z0-9._/-]{4,}|/data/(user/[0-9]+|data)/[A-Za-z0-9._-]+/(files|cache)/(encrypted_media|recordings|upload-lease))"
while IFS= read -r -d '' entry; do
  strings -a < "$entry" > "$strings_output" || fail "无法扫描 APK 条目"
  if rg -q -i "$secret_pattern" "$strings_output"; then
    fail "APK 的 DEX/resources/native 或其它条目包含凭据、私有 URL、对象键或绝对媒体路径"
  fi
done < <(find "$extract_root" -type f -print0)

echo "release 源码与 APK 隐私扫描通过"
