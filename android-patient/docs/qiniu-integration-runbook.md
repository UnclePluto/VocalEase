# 七牛私有空间联调 Runbook

## 前置条件

仅使用隔离测试私有空间、测试患者、测试歌曲和 HTTPS callback。访问密钥、私钥、上传 token 与对象 URL 只放入本地环境或密钥系统，禁止写入命令历史、文档、截图和日志。记录标识时统一使用后 8 位。

本文原有的隔离空间、大文件分片与恢复专项仍待完整验收。2026-09-29 已按用户明确授权，使用其指定的线上测试患者完成短录制上传验证，结果见文末；不据此标记全部专项通过。

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

## AAC 模拟器配置与实际上传验收

### 模拟器配置

Pixel_7 的 Google Play 系统镜像不允许 `adb root`，其录像档位默认使用 AMR。
使用已有的 Google APIs 可调试镜像，启动时开启前摄和临时可写系统：

```sh
emulator -avd VocaEase_API_37 -port 5556 -no-snapshot -writable-system \
  -camera-front emulated -camera-back emulated -gpu swiftshader
adb -s emulator-5556 root
adb -s emulator-5556 pull /vendor/etc/media_profiles_V1_0.xml /tmp/media-profiles-original.xml
```

仅在副本中将 `EncoderProfile/Audio` 设置为 `codec="aac"`、`bitRate="96000"`、
`sampleRate="48000"`、`channels="1"`，保存为 `/tmp/media-profiles-aac.xml`。
保留其他配置。只对专用模拟器执行以下操作，勿对物理设备执行：

```sh
adb -s emulator-5556 remount
adb -s emulator-5556 push /tmp/media-profiles-aac.xml /vendor/etc/vocaease_media_profiles_aac.xml
adb -s emulator-5556 shell chmod 644 /vendor/etc/vocaease_media_profiles_aac.xml
adb -s emulator-5556 shell restorecon /vendor/etc/vocaease_media_profiles_aac.xml
adb -s emulator-5556 shell setprop media.settings.xml /vendor/etc/vocaease_media_profiles_aac.xml
adb -s emulator-5556 shell stop
adb -s emulator-5556 shell start
```

若 remount 要求重启，先重启、重新 root/remount，再写入配置。
修改前保存 `adb shell getprop media.settings.xml` 的原值。Android 在框架启动时缓存媒体档位，
只重启应用不会刷新。不要将配置放在 `/data/local/tmp`：框架进程可能无权读取该目录。

恢复：将 `media.settings.xml` 设置回原值（原为空时使用 `adb shell 'setprop media.settings.xml ""'`），
删除新增配置文件，再执行 stop/start。原始 `/vendor/etc/media_profiles_V1_0.xml` 始终不覆盖。

### 必须验证的实际行为

1. 手机号登录，无治疗计划也能选择原声、伴奏齐全的歌曲。
2. 播放线上伴奏，实际 CameraX 录制至少 10 秒。
3. 日志 `AudioConfigUtil` 的实际 MIME 为 `audio/mp4a-latm`，停止后进入本地回看并显示音视频检查通过。
4. 视频与独立音频均可播放，播放结束后能重新播放。
5. 从应用确认提交，音频和视频均收到七牛可信回执，最终会话处理完成。
6. 通过私有下载读取实际上传文件，核对音轨、视频轨、编码和时长。

### 已发现并修复的客户端问题

- Android MediaMuxer 的纯音频输出仍带 `mp42` 主品牌。七牛内容探测识别为 `video/mp4`，
  与 `audio/mp4` 上传策略不符，返回 403。抽取器在确认单 AAC 音轨后写入 `M4A ` 主品牌；
  文件长度、box 偏移、兼容品牌和 AAC 样本数据均保持不变。服务端 MIME 限制不放宽。
- 本地播放器在 `STATE_ENDED` 时单独调用 play 不会重播；开始播放前回到 0。
- 已有回归测试使用实际 MediaExtractor/MediaMuxer 和加密文件 ExoPlayer，核对样本 SHA-256 与实际重播。

来源：[MP4 注册品牌](https://mp4ra.org/registered-types/brands)、
[七牛上传策略与 MIME 探测](https://developer-doc.qiniu.com/products/kodo/development-guidelines/security/1-put-policy)。

模拟器配置仅用于验证 AAC 设备路径；未将应用升级为预发布 CameraX，也未将 AMR 转码为 AAC。
真机麦克风、耳机、摄像头及真人演唱效果仍需实机验收。线上算法执行器如为 mock，不能作为评分质量证据。

### 2026-09-29 验收证据

- Android API 37 可调试模拟器，使用实际 CameraX 前摄和麦克风录制，线上《成都》伴奏。
- 实际会话 `3960bee0` 无治疗计划，最终 `completed`。
- 音频回执 `ready / audio/mp4 / 173315 bytes`，私有下载 HTTP 200；
  ffprobe 确认只有 AAC 音轨、48000 Hz、13.887979 秒、M4A 主品牌。
- 视频回执 `ready / video/mp4 / 1809944 bytes`，私有下载 HTTP 200；
  ffprobe 确认 H.264 视频与 AAC 音频，13.895833 秒。
- 安卓本地视频/独立音频可播放，演唱回顾页云端视频能加载实际画面并播放。
- 修复前：重播设备测试失败；抽取测试的品牌断言收到 mp42；真实音频上传 HTTP 403。
- 修复后：8 项相关设备测试通过；320 项 JVM 测试通过；Release lint、构建、签名和源码/APK 隐私扫描通过。
- 临时诊断日志已从交付源码和 APK 移除。
- 旧格式失败测试草稿已暂停，避免持续无效重试；新的录制使用修复后的格式。
- 模拟算法结果仍为 `is_mock=true`，不代表真实评分质量。

最终签名包复验：会话后 8 位 `56d411c2`，实际录制约 20 秒，视频播放结束后可从头重播；
音频、视频均 `ready`，会话 `completed`，两份私有下载均 HTTP 200。音频 257224 bytes，
视频 2471479 bytes。短文件上传过程中发生等待，最终自动完成；大文件分片和弱网专项仍按前文单独验收。

交付 APK：`app/build/outputs/apk/online-test/vocaease-aac-online-test-20260929.apk`（本地测试签名）。
SHA-256：`88155f6f98d80cbf6cf753bb6af1dd6d6c31b3346220c03a8e582311be67160a`。
