# Task 11 病人数据与演唱回放分析报告

## RED / GREEN

- RED：`pnpm test --run src/features/singing` 在实现前因 `PlaybackClock`、`WaveformPlayer`、`SingingDetailPage` 均不存在而有 3 个导入失败套件。
- GREEN：新增播放时钟、WaveSurfer 回放、患者数据与演唱详情后，同一命令为 `3 passed, 5 passed`。

## 实现

- `/patients/:patientId/data` 仅通过患者详情 API 取完整授权资料，通过管理端演唱历史 API 做日期筛选和服务端分页；列表入口进入单次详情。
- `/singing/:sessionId` 仅在详情页拉取完整分析与每个媒体的短期私有 URL；混合演唱录音为唯一可播放轨道，未产出的仅人声/仅伴奏明确禁用。
- WaveSurfer.js `7.11.1` 使用默认 HTMLMediaElement 后端与 Timeline、Regions、Hover 插件；嗳气事件建为短 Regions，并在卸载时销毁实例。
- `PlaybackClock` 以 WaveSurfer 绑定的音频元素为主时钟；录像仅在漂移超过 0.25 秒时校正。拒绝播放只抛出一次给用户操作层处理，不自动重试。
- Waviz 包版本 `1.0.0` 已按 npm tarball 的 `dist/index.d.ts` 与 README 核验：ESM 导出 `Waviz`，构造为 `(canvas, audioSource, audioContext?)`，提供 `simpleBars/stop/cleanup`。适配器只从播放动作创建；包加载或运行不兼容时改用 Web Audio + Canvas fallback，并保持 `start/stop/destroy` 生命周期。

## 验证

- `pnpm test --run src/features/singing`：通过（3 文件、6 测试），包括视频漂移校正和同一资产仅刷新一次。
- `pnpm typecheck`、`pnpm lint`、`pnpm build`：通过。
- Browser 插件不可用；未开始真实浏览器与 Compose 验收。全量 Vitest 启动后没有完成输出，已终止，不能将其记录为通过。

## 已知限制

- 浏览器与 Compose 验收未完成，不能替代真实私有跨域 URL / 七牛 CORS 冒烟。Browser 插件不可用，后续应使用 Playwright CLI。
- 未修改 `docs/design.pen`；Pencil 未打开，像素复验留给 Task 13。

## 收尾补充（第二轮）

### RED / GREEN

- RED：补充的患者数据页错误分离/`request_id`/重试、患者角色守卫、空态与明细跳转用例，以及回放的旧 URL 围栏、播放拒绝、录像失败降级、每资产一次刷新和资源释放用例，在原实现上分别失败。
- GREEN：`pnpm test --run src/features/singing` 通过（5 文件、18 用例）。覆盖 PlaybackClock 主时钟驱动指标、视频漂移校正、Regions、无真实分轨禁用、Waviz 1.0.0 延迟初始化与 Canvas fallback、AudioContext/RAF/WaveSurfer 的清理。

### 最终实现与边界

- 病人数据页分别请求授权详情和历史；各自错误、`request_id`、重试与取消信号相互独立。筛选历史只表述为“筛选结果”，演唱汇总明确以数据管理页的服务端口径为准。编辑按钮仅在详情已加载后复用 `PatientFormModal`，不会用脱敏列表数据回填。
- 演唱详情仅接受真实的 `singing_audio` 作为混合回放；未产出的 vocal/accompaniment 始终禁用，绝不把源文件伪装成分轨。详情、私有 URL 与播放期授权失败均显示稳定错误和 `request_id`，并提供受控重试。
- 私有 URL 与播放回调均有会话/实例版本围栏；过期响应、旧 media 事件和旧会话不能写回当前轨道。录像错误在一次刷新失败后降级为音频回放；播放被拒绝维持暂停并提示再次点击。
- 波形容器和历史表格在自身容器横向滚动；390px 下不把宽图表扩展到 body。未修改 `docs/design.pen`。

### 最终验证

- `pnpm test --run src/features/singing`：通过，5 文件 / 18 用例。
- `pnpm test --run --reporter=dot`：前端全量测试执行完成，控制台无本任务引入的警告或未处理请求。
- `pnpm typecheck`、`pnpm lint`、`pnpm build`：通过。
- `docker compose -f deploy/compose.yaml up -d --build`：通过；`web`、`server`、PostgreSQL、Redis 与 Celery 健康，`/health/live/` 返回 `ok`。临时 QA 用户、患者、治疗计划、歌曲和会话已全部删除。
- Browser 插件不可用。Playwright CLI 的临时依赖下载连续受到内部 npm 镜像 `ECONNRESET` 阻断；未将其标记为通过。使用本机 Chrome 对 Compose 的登录页进行了桌面/390px 截图冒烟，随后清理截图与临时浏览器进程。任务页关键交互以 Vitest + MSW 的真实 DOM 回归覆盖。
