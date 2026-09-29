# 曲库人工上传实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 管理员可人工上传必需原曲及可选人声、伴奏、LRC，维护资源并试听、发布。

**Architecture:** Song 直接关联人工资源，复用现有上传意图、媒体完成回执和私有访问。人工录入与分析状态分开，旧上传与患者原曲播放保持兼容。前端共用逐文件上传控制器，新建和补传分别提交。

**Tech Stack:** Python 3.13+、Django 5.2、DRF、pytest；React 19、TypeScript、Ant Design、TanStack Query、Vitest/MSW；本地/七牛存储。不新增依赖。

**Spec:** `docs/superpower/specs/2026-09-29-manual-song-upload-design.md`（已获用户确认）。

## Global Constraints

- 原曲必传；纯人声、纯伴奏、LRC 选填；新建默认未发布。
- 音频 MP3/WAV/FLAC，最大 50MB；LRC 为 UTF-8/BOM，最大 1MB；遵循服务端更低限制，禁止空文件。
- 人工上传不创建分析任务；旧歌曲模式默认为 existing，新人工歌曲为 manual，模式不可编辑。
- 仅管理员操作；绑定必须校验歌曲归属、类型、READY 状态、真实对象与可信回执。
- 选填资源可补传或替换，不新增删除或原曲替换界面；替换失败保留旧关联和发布状态。
- 不新增患者音轨切换、自动分离、自动识别、批量导入、音频同步检测。
- 所有界面、Git 操作描述及提交说明使用中文；流程文档存放在 docs/superpower/。

## Review Focus

1. 已选文件上传成功后再次换文件：旧资产不得用于新提交（任务 5）。
2. 原曲存储暂时不可用：人工发布仍须失败且不能伪造可用状态（任务 3）。
3. LRC 校验时实际读到的内容超过限制或与回执不一致：受限读取并拒绝（任务 2）。
4. 两名管理员同时替换同类资源：后者收到冲突，不覆盖先保存者（任务 3、4）。
5. 快速切换音轨或关闭弹窗：迟到响应不能恢复旧播放或污染重新打开的表单（任务 5、6）。

## 文件职责与接口约定

以下路径相对仓库根目录。服务端命令在 server/ 执行，前端命令在 web-admin/ 执行。

- `server/apps/songs/models.py` 与新迁移 `migrations/0005_manual_song_resources.py`：模式和三个资源外键、数据库约束。
- 新建 `server/apps/songs/resources.py`：资源归属/存储验证、凭证、绑定、并发更新、私有试听；避免继续扩大 services.py。
- 新建 `server/apps/songs/lyrics.py`：纯 LRC 解析及错误行号。
- 新建 `server/apps/media/readers.py`：仅从后端可信位置有界读取歌词；复用现有本地安全打开机制。
- `server/apps/songs/services.py`：接入人工创建和发布；保留原曲快照维护行为。
- `server/apps/songs/{serializers,views,urls,selectors}.py`、`server/common/api/schema.py`：暴露新增字段/接口与响应文档；消除新增资源关联的 N+1 查询。
- 新建 `server/apps/songs/resource_views.py`：资源 PATCH、歌词 GET；原试听接口扩展 track 参数。
- `web-admin/src/features/songs/{types,api}.ts`：统一契约与保存恢复逻辑。
- 新建 `web-admin/src/features/songs/{manualUpload,useSongResourceUpload}.ts`：文件校验和逐文件上传状态。
- 新建 `web-admin/src/features/songs/{SongManualUploadModal,SongResourcesModal,SongResourceFields}.tsx`：新建、资源维护、共用文件选择区。
- 修改 `web-admin/src/features/songs/{SongListPage,AudioPlayer}.tsx`：入口、状态、试听与歌词。

管理员歌曲响应新增 ingestion_mode、vocal_asset、accompaniment_asset、lyrics_asset、artifacts（source/vocal/accompaniment/lyrics 四个布尔值，表示已绑定 READY 未删除资产；访问时仍实时验证）。患者响应字段保持原样。

上传凭证请求新增 media_type，默认 song_source；选填资源必须指定已有 song_id 或有效原曲上传意图。新建请求新增 ingestion_mode（默认 existing）及三个可选 UUID，省略表示无资源。

`PATCH /api/v1/admin/songs/{id}/resources/` 请求格式为 `{updates: {vocal_asset: UUID}, expected: {vocal_asset: UUID|null}}`；每个更新键必须有对应 expected，可用键仅为三个选填字段，updates 不可为空、不可含 null，响应为更新后的 Song。

`POST /api/v1/admin/songs/{id}/preview/` 接受 `{track: "source"|"vocal"|"accompaniment"}`，缺省 source，响应格式不变。`GET /api/v1/admin/songs/{id}/lyrics/` 返回 `{lines: [{time_ms: number, text: string}]}`。缺失资源返回明确 404；归属或回执冲突 409；输入错误 400；存储暂不可用 503，沿用项目错误信封。

## 任务 1：人工歌曲数据与创建契约

**Files:** 修改 models.py、serializers.py、services.py、selectors.py；新建 resources.py、0005_manual_song_resources.py、`server/apps/songs/tests/test_manual_song_resources.py`。

**Interfaces:** 产出 `validate_song_resource(*, song_id: UUID, asset_id: UUID, media_type: str) -> MediaAsset`、`issue_optional_song_upload_grant(*, actor, request_id: str, song_id: UUID, media_type: str, mime: str, size: int)`，后者返回 `(song_id, asset, grant)`，与现有原曲凭证一致。

- [ ] 写失败测试 `test_manual_source_only_creates_draft_without_tasks`：断言 201、manual、draft、选填字段为 null、AnalysisTask 数量为 0；`test_manual_requires_source` 断言缺原曲为 400。
- [ ] 添加参数化测试：跨歌曲/错误类型/未完成/已删除/回执不一致资产不可绑定，失败无歌曲残留；manual + auto_analyze=true 为 400；旧请求仍 existing。
- [ ] 执行 `uv run --no-sync pytest apps/songs/tests/test_manual_song_resources.py -q`，确认因缺少功能失败。
- [ ] 增加模式与外键迁移、模式 check constraint、响应字段和事务创建；将选填验证加入创建流程，保留原曲专属上传意图要求。拒绝 existing 请求绑定人工选填资产及通用编辑修改模式/选填资源。
- [ ] 扩展上传凭证分流：原曲走现有路径；其他类型必须关联有效歌曲/上传意图，校验音频或歌词 MIME 与大小。不改写原曲意图。
- [ ] 重跑上述测试及 `uv run --no-sync pytest apps/songs/tests/test_song_api.py -q`，预期通过；检查迁移无遗漏。仅提交本任务文件，提交说明「新增人工歌曲模式与资源绑定」。

## 任务 2：LRC 有界读取与解析

**Files:** 新建 lyrics.py、media/readers.py、`server/apps/songs/tests/test_lyrics.py`、`server/apps/media/tests/test_asset_readers.py`；修改 resources.py 绑定校验。

**Interfaces:** `parse_lrc(content: bytes) -> list[dict]` 返回 time_ms/text，解析错误含行号；`read_verified_asset_bytes(*, asset: MediaAsset, max_bytes: int) -> bytes` 读取完成后验证内容与回执；`read_song_lyrics(*, song: Song) -> list[dict]`。

- [ ] 写测试 `test_lrc_bom_multiple_tags_offset_and_stable_order`：输入 BOM、[offset:-500]、同一行 [00:01.00][00:02.000] 与相同时间其他行，断言 500/1500 毫秒、正文和稳定顺序；覆盖负结果归零及空白分隔。
- [ ] 参数化覆盖空文件、仅元数据、非 UTF-8、[00:60]、未知标签、无时间正文、超限，断言明确错误与行号；有效 ti/ar/al/by/re/ve/length 可通过。
- [ ] 写读取测试：伪造长度、读取超过 max_bytes、读取期间对象版本变化、七牛超时/异常/重定向、回执不匹配均拒绝；本地正常安全打开与七牛受控响应读取成功。
- [ ] 执行 `uv run --no-sync pytest apps/songs/tests/test_lyrics.py apps/media/tests/test_asset_readers.py -q`，确认失败。
- [ ] 实现纯解析器；读取最多 max_bytes+1，设置网络超时，禁止任意客户端 URL 和重定向；本地复用 generation 绑定的安全打开，七牛仅使用后端签发地址并验证实际内容的七牛 ETag。验证后调用解析器，绑定非法 LRC 必须回滚。
- [ ] 重跑上述测试及任务 1 测试，预期通过；提交说明「新增歌词解析与可信内容读取」。

## 任务 3：资源维护、试听、发布与 API 文档

**Files:** 修改 resources.py、services.py、views.py、serializers.py、urls.py、common/api/schema.py；新建 resource_views.py、`server/apps/songs/tests/test_manual_song_api.py`。

**Interfaces:** `update_song_resources(*, actor, request_id: str, song: Song, updates: dict, expected: dict) -> Song`；`preview_song_resource(*, actor, request_id: str, song: Song, track: str) -> PrivateUrl`；路由与数据见接口约定。

- [ ] 写 `test_resource_compare_and_swap_preserves_published_song`：首次替换成功且 published 保持，旧 expected 再提交为 409、关联不变；错误资源替换后旧资产仍可试听且未物理删除。
- [ ] 写发布测试：manual 仅原曲可发布且无任务；原曲失效/存储暂不可用不发布；existing 无分析不能发布；manual 重新分析拒绝；通用编辑不能转换模式。
- [ ] 写试听/歌词测试：三类音轨各指向当前正确资产，默认仍原曲；缺失 404、已删除不可读、患者/医生不能访问管理员接口；歌词返回规范化行。患者已发布人工歌曲的列表/原曲播放仍可用。
- [ ] 执行 `uv run --no-sync pytest apps/songs/tests/test_manual_song_api.py -q`，确认失败。
- [ ] 实现事务加锁、expected 检查、审计与路由；人工发布分支只免除分析要求，继续调用真实原曲验证。为新接口补齐 drf-spectacular 信封与错误声明。
- [ ] 执行 `uv run --no-sync pytest apps/songs apps/analysis/tests/test_mock_executor.py -q` 和 `uv run --no-sync python manage.py spectacular --file /tmp/vocaease-manual-song-schema.yaml --validate --fail-on-warn`，预期通过；提交说明「打通人工歌曲资源维护试听与发布」。

## 任务 4：前端契约与可靠保存

**Files:** 修改 types.ts、api.ts、api.test.ts。

**Interfaces:** 新增 `SongResourceField = 'vocal_asset' | 'accompaniment_asset' | 'lyrics_asset'`、`SongTrack = 'source' | 'vocal' | 'accompaniment'`；`requestSongResourceGrant(file, mediaType, songId, signal?)`、`updateSongResourcesReliably(id, {updates, expected}, signal?)`、`getSongLyrics(id, signal?)`；requestPreview 在现有 signal 参数之后增加可选 track，保持旧调用兼容。

- [ ] 为 createSongReliably 添加测试：重试出现 song_id_exists 后，比对模式与三个资产及已有基本信息；省略选填字段按 null、模式按 existing 比较，不一致必须报冲突。
- [ ] 为更新添加测试：响应丢失后 GET 与目标一致则成功，否则保留错误；409 且远端不同不得覆盖，网络错误不能伪装成功；未改变字段不进入 updates。
- [ ] 执行 `pnpm test --run src/features/songs/api.test.ts`，确认新断言失败。
- [ ] 实现类型和 API，沿用 apiRequest、错误信封与 AbortSignal。创建网络失败可在用户重试时通过固定 id 对账；更新重试沿用原 expected，必要时查询核对目标。
- [ ] 重跑测试与 `pnpm typecheck`，预期通过；提交说明「扩展人工歌曲接口与保存恢复逻辑」。

## 任务 5：人工上传和补传界面

**Files:** 新建 manualUpload.ts、useSongResourceUpload.ts、SongManualUploadModal.tsx、SongResourcesModal.tsx、SongResourceFields.tsx 及 `SongManualUploadModal.test.tsx`、`SongResourcesModal.test.tsx`；修改 SongListPage.tsx 和对应测试。

**Interfaces:** `SongManualUploadModal({open,onCancel,onDone})` 与原上传弹窗回调一致；`SongResourcesModal({song,open,onCancel,onDone})`；`useSongResourceUpload({songId?})` 产出 slots、busy、chooseFile、uploadSelected、reset。slots 按媒体类型保存 file/assetId/status/progress/error；uploadSelected(signal) 返回 songId 和已确认资产映射。

- [ ] 写测试：原曲必填、可选部分或全部文件、格式/空文件/大小错误、manual 请求且无 auto_analyze；新建成功刷新列表。
- [ ] 写重试测试：原曲先取得 song_id，后续文件复用；第二个文件失败后重试不重传已确认资源；换文件清除该槽位 assetId；选择新原曲时重置新建上传意图及已确认选填资产。
- [ ] 写取消、重复提交和迟到回调测试；补传仅提交变化字段与旧 expected，冲突保持提示并刷新当前资源，不自动覆盖。
- [ ] 执行 `pnpm test --run src/features/songs/SongManualUploadModal.test.tsx src/features/songs/SongResourcesModal.test.tsx`，确认失败。
- [ ] 实现顺序逐文件上传与独立状态；所有选中文件确认后才保存，复用 uploadWithGrant/confirmUpload。本地文件校验只作即时提示，服务端仍权威。复用 Ant Design 表单和现有布局。
- [ ] 在列表新增人工上传、人工歌曲资源维护入口与资源标签；人工歌曲禁用分析轮询/重新分析入口、不显示模拟分析标签，并允许点击发布交由服务端校验。
- [ ] 运行两个新弹窗测试及 SongListPage、SongUploadModal 原有测试，预期通过；提交说明「新增人工上传与歌曲资源维护界面」。

## 任务 6：多音轨试听与歌词查看

**Files:** 修改 AudioPlayer.tsx、AudioPlayer.test.tsx、SongListPage.tsx；需要样式时仅修改现有歌曲相关样式文件。

**Interfaces:** AudioPlayer 继续接收 song/artifacts/onClose，从任务 4 API 获取音轨签名地址和歌词行。

- [ ] 写测试：三条音轨按资源状态启用，点击发送正确 track；歌词仅存在时读取，按 time_ms/text 显示、正文纯文本渲染；缺失资源不发无效请求。
- [ ] 写竞态测试：快速切换后仅最新 URL 播放，旧请求失败不覆盖新状态；关闭停止播放并取消请求；失效地址仅对当前音轨刷新一次。
- [ ] 执行 `pnpm test --run src/features/songs/AudioPlayer.test.tsx`，确认新行为失败。
- [ ] 实现音轨选择、私有地址刷新及歌词列表；列表向播放器传入服务端 artifacts，兼容原有 source-only 歌曲。
- [ ] 重跑 AudioPlayer 与 SongListPage 测试，预期通过；提交说明「支持人工歌曲多音轨试听与歌词查看」。

## 任务 7：集成验证与交付

**Files:** 已有受影响测试夹具、README.md（人工上传使用说明）；不新增无关重构。

- [ ] 补充端到端契约集成用例：真实本地上传原曲和三个资源→创建→试听/歌词→发布→患者列表→替换伴奏；七牛采用现有模拟凭证/回执测试验证等价行为，不需要真实账号。
- [ ] 执行 `uv run --no-sync pytest -q`、`uv run --no-sync python manage.py check`、`uv run --no-sync python manage.py makemigrations --check --dry-run` 及任务 3 的 schema 命令，全部预期通过。真实数据库并发用例使用仓库 PostgreSQL 测试环境；不可用时明确报告未验证，不能以 SQLite 测试代替锁语义结论。
- [ ] 执行 `pnpm test --run`、`pnpm typecheck`、`pnpm lint`、`pnpm build`，全部预期通过。运行仓库已有浏览器测试方式检查人工上传按钮、窄屏表单、音轨切换和错误提示。
- [ ] 在 README.md 记录四种文件、必填/大小规则、人工发布与补传方式；提交说明「补充人工上传集成验证与使用说明」。
- [ ] 依据选定执行方式完成代码审查，修复实质问题后仅重跑受影响检查；记录最终验证结果与任何环境限制，再进入分支交付流程，不自动部署。

## 自检与执行交接

规格中的创建、可选资产、归属验证、LRC、发布、兼容、失败恢复与并发均对应以上任务；五项 Review Focus 已落实到测试步骤。接口命名与读写数据结构保持一致。本计划尚待用户审核并选择执行方式，未开始产品代码实现。
