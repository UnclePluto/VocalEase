#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_ANDROID_JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
ANDROID_JAVA_HOME="${VOCAEASE_JAVA_HOME:-$DEFAULT_ANDROID_JAVA_HOME}"

cd "$PROJECT_ROOT"

if [[ ! -x "$ANDROID_JAVA_HOME/bin/java" ]]; then
    echo "未找到 Android 构建所需的 JDK 17，请通过 VOCAEASE_JAVA_HOME 指定。" >&2
    exit 1
fi

docker compose up -d --wait database redis

uv run --package vocaease-api pytest services/api/tests
uv run --package vocaease-worker pytest services/worker/tests
uv run ruff check services

pnpm --filter @vocaease/admin-web typecheck
pnpm --filter @vocaease/admin-web test
pnpm --filter @vocaease/admin-web build

(
    cd apps/android
    JAVA_HOME="$ANDROID_JAVA_HOME" ./gradlew testDebugUnitTest assembleDebug
)

docker compose build api worker admin-web
