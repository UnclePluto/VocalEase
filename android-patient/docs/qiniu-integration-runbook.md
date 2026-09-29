# 七牛私有空间联调 Runbook

## 前置条件

仅使用隔离测试私有空间、测试患者、测试歌曲和 HTTPS callback。访问密钥、私钥、上传 token 与对象 URL 只放入本地环境或密钥系统，禁止写入命令历史、文档、截图和日志。记录标识时统一使用后 8 位。

当前环境没有七牛凭据、bucket、upload host 或 callback 配置，以下项目全部待外部联调，本文不代表已通过。

## 隔离测试作用域

每次联调必须新建 `qa13-` 加 8 位十六进制字符的 run-id，并先用 `qa_e2e` 创建带服务端来源标记的管理员。测试患者必须由该管理员通过真实管理端流程创建；清理命令会交叉核对 run-id 管理员标记、`patient.create` 审计标记和媒体的数据库患者归属，不接受一个孤立的患者 UUID 作为删除依据。

从仓库根目录创建 QA 管理员时，明确使用本地 settings、Local 媒体后端与 local 命名空间：

```bash
cd server
DJANGO_SETTINGS_MODULE=vocaease.settings.local \
MEDIA_BACKEND=local \
MEDIA_ENVIRONMENT=local \
uv run --no-sync python manage.py qa_e2e \
  --run-id "$RUN_ID"
```

随后由这个管理员通过真实管理端创建本轮专用患者，临时取得完整 `$PATIENT_ID`；验收记录仍只保留其后 8 位。

在连接七牛前，使用同一数据库让服务端派生不可由操作员自行选择的对象前缀：

```bash
cd server
DJANGO_SETTINGS_MODULE=vocaease.settings.local \
MEDIA_BACKEND=local \
MEDIA_ENVIRONMENT=local \
uv run --no-sync python manage.py cleanup_qiniu_test_run \
  --run-id "$RUN_ID" \
  --patient-id "$PATIENT_ID" \
  --derive-prefix
```

命令输出 `object_prefix=...`。将末尾斜线去掉后作为 `$TEST_MEDIA_ENVIRONMENT`；它的格式为 `test-<run-id>-<服务端 HMAC 患者作用域>`，符合真实媒体对象键的环境标识规则且不含患者 UUID。必须同时使用专用测试私有 bucket，绝不能把测试命名空间指向生产 bucket。测试期间不得更换 settings 或其 `SECRET_KEY`，否则无法重新派生相同作用域。

启动联调服务前确认 `MEDIA_BACKEND=qiniu`、`MEDIA_ENVIRONMENT` 与上述前缀完全一致，并通过密钥系统注入七牛配置。不要把配置值拼进脚本、工单或 shell trace；禁止使用 `set -x`。完成配置后再创建演唱 session 和上传授权，确保本轮音视频对象都自然落入该前缀。

本 runbook 的联调与清理命令统一使用 `DJANGO_SETTINGS_MODULE=vocaease.settings.local`。联调进程必须显式设置 `MEDIA_BACKEND=qiniu`、`MEDIA_ENVIRONMENT=$TEST_MEDIA_ENVIRONMENT`，并由密钥系统提供 `QINIU_ACCESS_KEY`、`QINIU_SECRET_KEY`、`QINIU_BUCKET`、`QINIU_DOMAIN`、`QINIU_CALLBACK_URL`；`QINIU_UPLOAD_URL` 使用已审核的 HTTPS 上传入口。`MEDIA_UPLOAD_GRANT_TTL_SECONDS` 决定 quiet gate 的最短等待时间。以上配置必须指向同一数据库和专用测试私有空间。

## 核心闭环

1. 准备一份大于 50MiB 的 AVC/AAC MP4，创建演唱 session 并确认本地为 VEF1 密文。
2. 使用 Android SDK 8.9.0、分片 V2 和应用私有 recorder 上传音频、视频，记录 job/session/asset 后 8 位及开始/完成时间。
3. 上传到中途杀进程，重启后确认只续传剩余分片；不得重新创建 session、asset 或分析任务。
4. 分别验证 Wi‑Fi→移动网络、移动网络→Wi‑Fi、断网→恢复；状态应进入等待网络并复用持久幂等键。
5. 暂停后确认 SDK 与明文 lease 均停止；继续后只恢复未完成媒体。
6. 让上传凭据过期，确认重新申请的凭据仍绑定同一 asset/object，且 token 不落 Room/WorkData/日志。
7. 对同一对象发送重复可信 callback；服务端应幂等，客户端 confirm 先 detail 仲裁。
8. 分别确认音频与视频；缺任一 confirm 时 submit 必须拒绝，两者 ready 后才可 submit。
9. 对 submit 制造超时并用同一幂等键重试，确认只产生一个 analysis generation/task 集合。
10. 等待 processing→completed，从历史进入结果，确认仅 `is_mock=true` 显示“演示结果”。

每一步保存：应用版本、设备/系统、网络类型、时间、状态转换、HTTP 安全错误分类、后 8 位标识和缺陷号。不得保存请求/响应 body、Authorization、upload token、object key 或完整 URL。

## 失败与恢复专项

- callback 长时间未到：验证持久 deadline、退避、杀进程恢复和最大等待后的可恢复状态。
- 上传完成但 WAITING_RECEIPT 落盘前崩溃：重启先 detail；远端 ready 时不得重新申请或重传。
- pause→continue 后旧请求迟到：旧 operationVersion 不得覆盖新代际。
- 401/403 私有回放：仅刷新一次 URL 并保留播放位置，退出时等待 Media3 release。

## 测试后清理

先执行只读预览。手工停止客户端或取消 WorkManager 只能减少测试噪声，不能替代服务端 quiet gate，也不是命令判定安全的依据：

```bash
cd server
DJANGO_SETTINGS_MODULE=vocaease.settings.local \
MEDIA_BACKEND=qiniu \
MEDIA_ENVIRONMENT="$TEST_MEDIA_ENVIRONMENT" \
uv run --no-sync python manage.py cleanup_qiniu_test_run \
  --run-id "$RUN_ID" \
  --patient-id "$PATIENT_ID" \
  --object-prefix "$OBJECT_PREFIX" \
  --dry-run
```

核对输出中的 session、asset 和 Kodo object 数量；dry-run 只列举 Kodo 前缀、不执行删除。输出只显示患者后 8 位和计数，不显示完整 session、asset 或 object key。预览产生的 opaque `confirmation_token` 绑定 run-id、患者、服务端派生前缀及当时的数据库与 Kodo 清单，10 分钟失效。任一侧清单变化时旧令牌必然拒绝，必须重新预览。

确认目标无误后，在 10 分钟内第一次提交确认：

```bash
cd server
DJANGO_SETTINGS_MODULE=vocaease.settings.local \
MEDIA_BACKEND=qiniu \
MEDIA_ENVIRONMENT="$TEST_MEDIA_ENVIRONMENT" \
uv run --no-sync python manage.py cleanup_qiniu_test_run \
  --run-id "$RUN_ID" \
  --patient-id "$PATIENT_ID" \
  --object-prefix "$OBJECT_PREFIX" \
  --confirm "$CONFIRMATION_TOKEN"
```

第一次确认绝不删除 Kodo 或输出 `kodo=verified`。命令持久停用本轮测试患者及其账号、取消该患者全部 session，并通过数据库行锁把作用域内资产置为 `pending_cleanup`。因此真实 API 的新 session/grant 会被患者有效性检查拒绝，现有资产 callback 会被状态机拒绝。quiet gate 审计记录绑定 run-id、患者和对象前缀摘要，并给出 `quiet_until`：它不早于 gate 启动时间加 `MEDIA_UPLOAD_GRANT_TTL_SECONDS`，且覆盖数据库中更晚的 upload expiry/lease。

到达 `quiet_until` 后重新执行上面的 dry-run，取得绑定最新数据库/Kodo 清单的新确认令牌，再用完全相同的 settings/env 执行第二次 `--confirm`。如果尚未到期，命令只输出 `quiet=waiting ... kodo=not_verified`；不要绕过等待。到期后命令会在固定稳定窗口前后重复读取数据库与 Kodo 前缀，任一侧有迟到对象或记录变化都会拒绝并要求重新 dry-run。

只有 quiet gate 已成熟且稳定窗口两次清单一致时，命令才逐个删除数据库已知对象及前缀内孤儿 Kodo 对象，并用 Kodo `stat` 和前缀重新列举共同确认命名空间为空。随后才硬删除关联分析任务及审计残留、session、binding 和 asset，并再次核验数据库为空；唯有这条路径会输出 `quiet=stable kodo=verified database=verified`。

如果删除请求或 Kodo 后验核验失败，命令保留数据库记录为 `pending_cleanup`，不得手工删库。修复网络或权限后重新 dry-run；已删除对象返回“不存在”会按幂等成功处理。若部署层额外引入了 callback 消息代理，本命令无法感知该外部队列，必须先按部署运维手册排空代理再确认删除。

服务端输出 `quiet=stable kodo=verified database=verified` 后，才能执行 QA 账号清理：

```bash
cd server
DJANGO_SETTINGS_MODULE=vocaease.settings.local \
MEDIA_BACKEND=local \
MEDIA_ENVIRONMENT=local \
uv run --no-sync python manage.py qa_e2e \
  --run-id "$RUN_ID" \
  --cleanup
```

再确认 SDK recorder、明文 upload lease、本地草稿与密文均清空，最后撤销临时凭据并清理本地终端中的敏感输入。验收记录仅保留 job/session/asset 后 8 位、计数、时间和缺陷号；不要保存完整患者 UUID、对象前缀、确认令牌、object key 或 Kodo 响应正文。

本仓库已用内存 Kodo 替身覆盖 dry-run 只列举不删除、不可伪造作用域、确认令牌双侧清单绑定、持久 grant/callback quiet gate、TTL/lease 等待、稳定窗口迟到写入、已知与孤儿对象删除/后验核验和数据库清理。当前环境没有真实七牛凭据，因此没有执行或声称完成实云清理；专用测试 bucket 的真实删除仍为外部待验收项。
