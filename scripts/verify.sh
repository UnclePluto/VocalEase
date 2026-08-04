#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_STUDIO_JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
HOMEBREW_JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
ANDROID_JAVA_HOME="${VOCAEASE_JAVA_HOME:-$ANDROID_STUDIO_JAVA_HOME}"
TEST_DATABASE_NAME="vocaease_verify"
TEST_DATABASE_URL="postgresql+psycopg://vocaease:vocaease_dev@127.0.0.1:54329/$TEST_DATABASE_NAME"
PLAYWRIGHT_WRAPPER="${HOME}/.codex/skills/playwright/scripts/playwright_cli.sh"
PLAYWRIGHT_SESSION="vocaease-admin-e2e"
ANDROID_ADB="${VOCAEASE_ADB:-${HOME}/Library/Android/sdk/platform-tools/adb}"
VERIFY_COMPOSE=(
    docker compose
    -p vocaease-verify
    -f compose.yaml
    -f compose.verify.yaml
)

cd "$PROJECT_ROOT"

if [[ ! -x "$ANDROID_JAVA_HOME/bin/java" ]]; then
    ANDROID_JAVA_HOME="$HOMEBREW_JAVA_HOME"
fi

if [[ ! -x "$ANDROID_JAVA_HOME/bin/java" ]]; then
    echo "未找到 Android 构建所需的 JDK，请通过 VOCAEASE_JAVA_HOME 指定。" >&2
    exit 1
fi

docker compose up -d --wait database redis

cleanup() {
    if [[ -x "$PLAYWRIGHT_WRAPPER" ]]; then
        "$PLAYWRIGHT_WRAPPER" -s="$PLAYWRIGHT_SESSION" close >/dev/null 2>&1 || true
    fi
    "${VERIFY_COMPOSE[@]}" down --volumes >/dev/null 2>&1 || true
    docker compose exec -T database \
        psql -U vocaease -d postgres -c "DROP DATABASE IF EXISTS $TEST_DATABASE_NAME" \
        >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${VERIFY_COMPOSE[@]}" down --volumes >/dev/null 2>&1 || true

docker compose exec -T database \
    psql -U vocaease -d postgres -c "DROP DATABASE IF EXISTS $TEST_DATABASE_NAME"
docker compose exec -T database \
    psql -U vocaease -d postgres -c "CREATE DATABASE $TEST_DATABASE_NAME"

export VOCAEASE_TEST_DATABASE_URL="$TEST_DATABASE_URL"
uv run --package vocaease-api pytest services/api/tests
uv run --package vocaease-worker pytest services/worker/tests
uv run ruff check services

pnpm --filter @vocaease/admin-web typecheck
pnpm --filter @vocaease/admin-web test
pnpm --filter @vocaease/admin-web build

(
    cd apps/android
    JAVA_HOME="$ANDROID_JAVA_HOME" ./gradlew \
        testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
    if [[ ! -x "$ANDROID_ADB" ]] && command -v adb >/dev/null 2>&1; then
        ANDROID_ADB="$(command -v adb)"
    fi
    if [[ -x "$ANDROID_ADB" ]] && "$ANDROID_ADB" devices | awk 'NR > 1 && $2 == "device" { found=1 } END { exit !found }'; then
        JAVA_HOME="$ANDROID_JAVA_HOME" ./gradlew connectedDebugAndroidTest
    else
        echo "未检测到已启动的 Android 设备，跳过仪器测试。"
    fi
)

"${VERIFY_COMPOSE[@]}" up -d --build --wait
VOCAEASE_DEMO_API_URL="http://127.0.0.1:18000" \
    uv run --package vocaease-api python scripts/prepare_demo.py
curl --fail --silent http://127.0.0.1:18000/api/v1/health >/dev/null
curl --fail --silent http://127.0.0.1:18080 >/dev/null

if [[ -x "$PLAYWRIGHT_WRAPPER" ]]; then
    (
        cd apps/admin-web
        "$PLAYWRIGHT_WRAPPER" \
            --config playwright-cli.json \
            -s="$PLAYWRIGHT_SESSION" \
            open http://127.0.0.1:18080
        browser_result="$("$PLAYWRIGHT_WRAPPER" \
            -s="$PLAYWRIGHT_SESSION" \
            run-code --filename=e2e/admin-smoke.js 2>&1)"
        echo "$browser_result"
        if [[ "$browser_result" == *"### Error"* ]]; then
            echo "真实浏览器冒烟验收失败。" >&2
            exit 1
        fi
    )
else
    echo "未找到 Playwright CLI 包装器，跳过真实浏览器冒烟验收。"
fi

echo "VocaEase 一期自动化验收已全部完成。"
