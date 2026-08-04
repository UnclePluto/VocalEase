#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

docker compose up -d --build --wait
uv run --package vocaease-api python scripts/prepare_demo.py

echo "VocaEase 一期内部测试环境已启动。"
echo "Web 管理后台：http://127.0.0.1:8080"
echo "Android 模拟器服务地址：http://10.0.2.2:8000"
