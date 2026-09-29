# VocaEase

VocaEase 是一个以唱歌训练辅助胃上嗳气治疗的全栈项目。本仓库交付医生后台、Django 共享服务和 Android 10+ 患者客户端，以及可替换真实算法的演示分析协议；不包含真实 AI 算法。

## Android 患者客户端

客户端位于 `android-patient/`，要求 JDK 17、Android SDK（platform 29/37，compileSdk 37）并设置 `ANDROID_HOME`。应用面向 Android 10（API 29）及以上竖屏手机，运行时不依赖 GMS、Firebase、Google 登录或 Google 在线接口，主导航仅“去唱歌 / 我的”。

依赖版本和校验文件已锁定。中国大陆开发环境应通过可访问的 Maven 代理或内部制品缓存先完成一次受控预热，再用严格校验和离线模式复跑；验证脚本本身不会下载依赖。debug 默认只允许模拟器通过 `http://10.0.2.2:8000/` 联调，release 必须显式提供完整 HTTPS 且以 `/` 结尾的 API 基址：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
cd android-patient
./gradlew --dependency-verification strict :app:assembleDebug
export VOCAEASE_API_BASE_URL=https://api.example.invalid/
./scripts/verify_release.sh
```

本地服务端联调先按下文启动 Django，再安装 debug APK；Android 模拟器使用 `10.0.2.2` 访问宿主机。患者闭环定向验证：

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew --offline :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.e2e.PatientClosedLoopTest
```

当前自动化证据覆盖 API 29/API 34 模拟器。七牛真实私有空间、Android 17/API 37、小米/荣耀/OPPO/vivo/华为实体机及本轮 Pencil 只读复核仍是外部待验收项，详见 `android-patient/docs/`；不得把模拟器证据视为国产真机或无 GMS 全闭环验收。

## 本地启动

先安装 Docker Desktop（含 Docker Compose）。如需在宿主机运行测试，还需要 Python 3.13+、[uv](https://docs.astral.sh/uv/) 和 Node.js 24+（项目使用 pnpm 10）。

```bash
cp .env.example .env
docker compose --env-file .env -f deploy/compose.yaml up --build -d
docker compose --env-file .env -f deploy/compose.yaml ps
```

`server` 容器启动时会自动执行数据库迁移。所有服务健康后，初始化幂等演示数据：

```bash
docker compose --env-file .env -f deploy/compose.yaml exec server \
  uv run --no-sync python manage.py seed_demo
```

`seed_demo` 可以重复执行，不会重复创建演示账号、患者、歌曲、媒体、演唱记录或分析结果。

管理员可在曲库点击「人工上传」，填写歌曲信息并上传原曲；纯人声、纯伴奏与 LRC 歌词按需选择。歌曲时长会从原曲文件自动读取，无需填写。音频支持 MP3、WAV、FLAC，单文件不超过 50MB；歌词使用 UTF-8 编码的 `.lrc`，不超过 1MB。人工歌曲先保存为草稿，不自动分析。确认原曲可用后，管理员可以发布；之后可从歌曲操作菜单的「管理资源」补传或替换选填文件，在试听窗口切换已上传音轨并查看歌词。

## 访问地址与演示账号

- 医生后台：http://localhost:3000
- Django API：http://localhost:8000
- OpenAPI 文档：http://localhost:3000/api/docs/
- OpenAPI Schema：http://localhost:3000/api/schema/
- 存活检查：http://localhost:8000/health/live/
- 依赖就绪检查：http://localhost:8000/health/ready/
- Django Admin：http://localhost:8000/internal/admin/

演示账号：

| 角色 | 登录账号 | 初始密码 |
| --- | --- | --- |
| 系统管理员 | `demo-admin` | `888888` |
| 医生 | `DDEMO001` | `888888` |
| 患者一 | `PDEMO001` | `888888` |
| 患者二 | `PDEMO002` | `888888` |

所有演示账号首次登录后必须修改密码。医生后台仅接受系统管理员或医生账号；患者账号供 Android 客户端和 API 联调使用。

## 本地媒体与七牛云

默认 `.env.example` 使用 `MEDIA_BACKEND=local`，媒体存放在 Compose 的私有卷中，不需要外部服务。

切换七牛云私有空间时，将 `.env` 中的 `MEDIA_BACKEND` 改为 `qiniu`，并填写：

```dotenv
QINIU_ACCESS_KEY=替换为访问密钥
QINIU_SECRET_KEY=替换为私有密钥
QINIU_BUCKET=替换为空间名
QINIU_DOMAIN=https://你的私有媒体域名
QINIU_CALLBACK_URL=https://你的服务域名/api/v1/media/qiniu/callback/
WEB_MEDIA_ORIGIN=https://你的私有媒体域名
```

七牛空间必须是私有空间，并为医生后台和患者端来源配置 CORS。浏览器访问仅使用短时授权地址；数据库不会保存永久公开 URL。`seed_demo` 始终使用本地存储生成离线演示数据，不依赖七牛凭据，也不会写入生产空间。

生产环境必须把 `DJANGO_SETTINGS_MODULE` 切换为 `vocaease.settings.base`，把 `MEDIA_BACKEND` 设为 `qiniu`、`MEDIA_ENVIRONMENT` 设为独立的生产命名空间（例如 `production`），并把 `.env.example`/Compose 中的 `change-me` 替换为至少 32 字节的随机 `DJANGO_SECRET_KEY`；三个 Django 运行服务都会透传同一 settings module，生产 settings 会拒绝空值、短值、示例密钥及 `local/test` 媒体命名空间启动。由 HTTPS 入口代理服务时，将 `DJANGO_DEBUG=false`、`DJANGO_SECURE_SSL_REDIRECT=true`，并在确认整个域名及所有子域只提供 HTTPS 后设置合适的 `DJANGO_SECURE_HSTS_SECONDS`（例如 `31536000`）、`DJANGO_SECURE_HSTS_INCLUDE_SUBDOMAINS=true` 和 `DJANGO_SECURE_HSTS_PRELOAD=true`。本地 HTTP 开发必须保持 `DJANGO_SETTINGS_MODULE=vocaease.settings.local`，并保持这些 HTTPS/HSTS 选项为 `.env.example` 中的关闭值。

生产 HTTPS 拓扑必须由同机或同一可信私网内的边缘代理访问 Compose `web` 入口；Compose 默认只把 `web` 和 `server` 绑定到宿主机 `127.0.0.1`，不得把 Django `server` 端口直接暴露到公网。边缘代理必须覆盖客户端传入的 X-Forwarded-Proto 与 Host，并把规范化后的单值 `X-Forwarded-Proto: https` 传给 `web`；内层 Nginx 只保留严格的 `http/https` 值，避免 TLS 终止后发生重定向循环。部署真实域名时还必须把 `DJANGO_ALLOWED_HOSTS` 改为 API/后台域名列表，并把 `AUTH_WEB_ALLOWED_ORIGINS` 改为带 `https://` 的完整后台来源，否则 Django Host 校验或 Web 刷新来源校验会拒绝请求。

## 测试与质量检查

服务端：

```bash
cd server
uv sync --frozen
uv run --no-sync pytest -q
uv run --no-sync python manage.py check
DJANGO_SETTINGS_MODULE=vocaease.settings.test \
  uv run --no-sync python manage.py makemigrations --check --dry-run
uv run --no-sync python manage.py spectacular \
  --file /tmp/vocaease-schema.yaml --validate --fail-on-warn
```

前端：

```bash
cd web-admin
pnpm install --frozen-lockfile
pnpm test --run
pnpm typecheck
pnpm lint
pnpm build
pnpm exec playwright install chromium
```

Playwright 验收必须使用独立 Compose project；下面示例中的 8 位后缀每次运行都要替换，且端口不得与其他项目冲突：

```bash
export VOCAEASE_E2E_RUN_ID=qa13-a1b2c3d4
export VOCAEASE_E2E_COMPOSE_PROJECT=vocaease-e2e-a1b2c3d4
export VOCAEASE_E2E_BASE_URL=http://localhost:13013
COMPOSE_PROJECT_NAME="$VOCAEASE_E2E_COMPOSE_PROJECT" WEB_PORT=13013 SERVER_PORT=18013 \
  AUTH_WEB_ALLOWED_ORIGINS=http://localhost:13013,http://127.0.0.1:13013 \
  docker compose -p "$VOCAEASE_E2E_COMPOSE_PROJECT" -f deploy/compose.yaml up --build -d
cd web-admin
pnpm exec playwright test
cd ..
docker compose -p "$VOCAEASE_E2E_COMPOSE_PROJECT" -f deploy/compose.yaml down -v --remove-orphans
```

`seed_demo` 和 `qa_e2e` 会在运行时拒绝非 `local/test` 环境。前者只负责可重复使用的固定演示基线；浏览器产生的医生、患者、歌曲、导出任务、媒体文件和大数据量患者均由唯一 run-id 及来源标记限定并硬清理。Playwright 默认删除本次鉴权、截图、下载和 trace 临时物；人工视觉复核时可临时设置 `VOCAEASE_E2E_KEEP_ARTIFACTS=1`，检查后必须手动清除对应 `/tmp/vocaease-*-${VOCAEASE_E2E_RUN_ID}` 目录。

完整容器验证：

```bash
docker compose --env-file .env -f deploy/compose.yaml config --quiet
docker compose --env-file .env -f deploy/compose.yaml up --build -d
docker compose --env-file .env -f deploy/compose.yaml ps
curl --fail http://localhost:8000/health/ready/
```

## OpenAPI

交互文档由 Django 服务端实时提供。需要生成可校验的 Schema 文件时运行：

```bash
cd server
uv run --no-sync python manage.py spectacular \
  --file /tmp/vocaease-schema.yaml --validate --fail-on-warn
```

患者端接口位于 `/api/v1/patient/`，后台接口位于 `/api/v1/admin/`，通用认证位于 `/api/v1/auth/`。

## 模拟算法声明

歌曲分析、演唱音频指标和面部关键点当前均为流程模拟器。模拟结果在数据库、API 和界面中显式标记 `is_mock: true`，仅用于开发和交互验证，不代表真实人声分离、MediaPipe 分析、临床诊断或治疗效果。

## 常见问题

- 服务未变为 healthy：先运行 `docker compose --env-file .env -f deploy/compose.yaml ps`，再查看对应服务日志。`server` 的就绪检查要求 PostgreSQL、Redis 和本地私有存储目录均可安全写入；Celery worker 与 beat 由 Compose 各自独立的健康检查保证。
- 登录后要求改密：这是固定初始密码账号的安全门禁，完成改密后重新登录即可。
- 媒体无法播放：确认本地私有卷可写；使用七牛时检查私有空间、回调地址、授权域名和 CORS。
- 浏览器刷新被拒绝：确保访问地址与 `AUTH_WEB_ALLOWED_ORIGINS` 完全一致，默认支持 `localhost:3000` 与 `127.0.0.1:3000`。
- 异步任务未完成：检查 `celery` 与 `celery-beat` 是否为 healthy，并查看任务日志中的任务 ID。

## 停止与清理

停止但保留数据：

```bash
docker compose --env-file .env -f deploy/compose.yaml down
```

彻底删除本地数据库和媒体卷：

```bash
docker compose --env-file .env -f deploy/compose.yaml down -v
```

最后一条命令会不可恢复地删除 Compose 管理的本地演示数据和媒体。当前 `web` 容器本身使用非 root Nginx 同时提供静态页面和 `/api/` 反向代理，因此没有额外重复的 Nginx 服务。
