# 演唱体验验证报告（2026-10-06）

后续用户授权线上部署和手机安装，最新状态见 [线上发布与手机安装记录](2026-10-06-production-deployment.md)。以下未部署等描述为首次本地验收时的历史状态。

工作树：`codex/singing-experience`。当前为实现及本地验证，未部署、未合并；真实病例、国产真机与生产七牛验收未完成，不能宣称全部体验验收完成。

## 已实现

1. 医生患者列表、筛选与两级详情返回。
2. 删除独立演唱回放卡，操作合并进声音波形；仅患者人声/患者人声＋会话绑定伴奏。
3. 患者音频驱动波形、实时dBFS/F0及深色时序/极坐标双图；暂停恢复复用同一音源，无随机或固定数据回退。
4. 安卓试听原唱/演唱伴奏默认，录制期间可切换；参考横块滚动、真实麦克风音高白点/过去轨迹。
5. 单一麦克风→AAC与音高，相机仅视频；统一取景和嘴部/下颌/颈部引导。同步元数据随加密草稿上传。
6. 歌曲参考音高版本、真实人声YIN生成、租约重试、会话固定媒体及权限授权合同。

## 本地验证证据

| 检查 | 结果与范围 |
|---|---|
| 服务端 `uv run --frozen pytest -q` | 570通过、32跳过（包括需要真实PostgreSQL的并发测试）；另有本地FFmpeg真实WAV220Hz解码、上传私有下载字节一致与绑定伴奏合同通过 |
| 服务端迁移 | `makemigrations --check --dry-run`无遗漏；最新禁止回退全库不变及历史升级边界定向18通过、1跳过 |
| Web `pnpm test --run` | 183通过；typecheck/lint/build通过 |
| Google Chrome真实媒体 | 5通过：220Hz双图/暂停恢复/两模式偏差≤100ms、静音、403授权、患者筛选返回、真实MP4迟到授权静音跟随≤100ms且主音频连续。WAV与MP4为实际解码；HTTP账户/授权合同使用隔离夹具 |
| 视觉 | Pencil只读核对design.pen H6DDrc/YoqZJ深色/黄红时域及橙红极坐标；1440、390浏览器截图已检查无页面横向溢出 |
| 安卓 | 343单元测试（新增必需schema_version编码回归）、lintDebug、assembleDebug/AndroidTest通过；API29模拟器11项、API34只读模拟器当前12项（390×844/字体1.3×）通过（真实采音、合并、加密迁移、界面、客户端状态机）；完整状态机媒体和七牛响应为夹具 |
| 安卓release | 使用部署文档已配置HTTPS基址，离线严格依赖校验、构建、权限、无GMS、网络策略、Room及源码/DEX资源隐私审计通过；不是上线或生产连接验证 |

截图保存在 `/tmp/vocaease-voice-board-1440.png` 与 `/tmp/vocaease-voice-board-390.png`（测试工件未提交）。独立测试配置要求本机Chrome（默认Playwright Chromium无H.264解码）与FFmpeg。隔离媒体浏览器命令：`pnpm exec playwright test --config playwright.media.config.ts`。

## 尚未完成的验收

- Docker守护进程未运行，标准Compose functional/visual与镜像内FFmpeg未验证；不能把独立HTTP夹具浏览器验证记成标准后端E2E通过。
- 没有授权真实演唱病例和目标国产真机，未验证10秒/3分钟/完整歌曲的真实采音、≤150ms白点延迟和≤100ms首尾同步。
- 未对生产验收歌曲执行ready检查或写入标注；没有自动部署、替换生产轨道或公开私有媒体。
- 七牛真实上传回执、CORS、签名刷新与跨设备医生回放尚待授权环境；本地220Hz合成PCM不是人类临床演唱证据。
- 安卓设计文件Pencil请求返回医生画板，安卓画板访问未验证；界面按用户图片和批准规格实现。

## 执行裁定与成本

1. Task机器标题格式改英文，中文正文不变：技能解析要求；成本是文档格式变化。
2. 解码PCM64MiB上限：限制内存；约35分钟以上需后续流式方案。
3. 保留旧单参数API/PreviewGrantSource重载：兼容认证测试替身；新调用须用模式/会话重载。
4. 复制H.264首关键帧，患者AAC起点偏移超过100ms拒绝发布：避免破坏首GOP；慢启动设备可能需重录，真机须量测。
5. UTP离线依赖缺失，用同样严格构建APK经adb运行：只替换启动器；保留原始设备运行结果，不能记成Gradle connected任务通过。
6. 增加最上层媒体依赖迁移屏障：保护最新schema回退不做任何修改；历史空库降级不再直接支持，恢复须备份。
7. 独立真实媒体浏览器配置使用授权HTTP夹具：Docker不可用仍可验证真实WebAudio/MP4；不能证明生产权限/七牛/病例闭环，MP4场景需本机Chrome/FFmpeg。
8. CameraX1.6.1编译期包访问首帧PTS：准确原点避免排队误差；升级CameraX必须复核内部字段和真机同步，不能随意升级后保留旧假设。
9. 人工核验曲轨起点并固定非负偏移：避免未验证错拍；现有曲目须先提交核验，负偏移资源须先补齐静音，旧会话不补造同步能力。

## 回归发现并修复

- 新迁移依赖使旧媒体屏障之前先回退其他迁移；新增最上层屏障，历史边界仍在屏障之前验证。
- 安卓默认JSON省略schema_version，实际设备提交会违反服务端合同；新增失败编码测试后强制编码该字段，设备提交闭环转绿。
- 医生伴奏授权迟到未采用批准的默认组合模式；新增异步授权测试后按可用状态选择默认，用户主动选择人声仍保留。

## 最后独立评审

已完成一次全分支只读评审（基线52a399f，评审HEAD23be492），发现5项Important、1项Minor，无有充分依据的Critical。按要求进行一次集中修复与回归，不再次派评审。以下五项均已保留失败回归并完成实现修复：

1. 周期元数据异常：首次/第二次写入IOException和账户lease失效均停止采集、清理明文并转Interrupted，不发布Reviewable；取消异常继续传播。退出的`.timing`遗漏另经RED→删除→GREEN。
2. 跟随授权打断主音频：患者媒体、波形与跟随者生命周期分开；迟到录像/伴奏和录像刷新不暂停患者节点或销毁分析器；患者自身授权换URL在加载后恢复进度及主动选择模式。
3. 视频原点：改用CameraX实际写入首个关键帧PTS，麦克风提前启动，合并裁切早于视频的AAC；CameraXFrameTimeTest在真实模拟器编码链人为延迟Started250ms仍保留实际原点，成片AAC首样本≤100ms。
4. 未核验曲轨：保存经授权医生/管理员人工核验的标记偏移及两份回执指纹，会话固定快照；无核验的组合与伴奏切换拒绝。1000→3000ms标记经2000ms映射，两端回归通过；回执变更使当前歌曲核验失效。
5. 裁剪终点：在有效首尾构造边界锚点，保留暂停和segment语义，10020ms停止裁到10000ms仍覆盖完整尾段。

暂缓小项（穷尽）：重复参考音高生成请求没有按歌曲/输入指纹/算法版本幂等复用，网络重试可产生多个分析版本与额外解码任务；现有租约只保护同一版本。按执行技能保留为Minor，未扩大本次修复范围。

所有真实环境未验项目继续保留，不用夹具填真实病例验收。


任务状态：任务1–11代码实现已验证；任务12完成本地验证，真实病例/国产真机/标准Compose/生产七牛等验收仍待，保留分支与工作树，不执行合并或部署。

关键日志：`/tmp/vocaease-review-server-final2.log`（服务端全套），`/tmp/vocaease-review-web-final.log`（后台单测），`/tmp/vocaease-review-browser-chrome.log`（Chrome5场景），`/tmp/vocaease-review-device-final2.log`（API34最终12项），`/tmp/vocaease-review-release-final.log`（发布完整验证）。元数据保存使用合法UUID的旧实现失败证据另见`/tmp/vocaease-metadata-valid-red.log`，恢复后完整343项见`/tmp/vocaease-metadata-valid-green.log`。

首帧适配的技术依据：CameraX Recorder先保存首个有效视频PTS、再将文件写入PTS归零；应用读取前者建立单调时钟映射。[AndroidX官方Recorder实现](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/camera/camera-video/src/main/java/androidx/camera/video/Recorder.java)。本项目额外按固定1.6.1字节码核对该字段，并用实际设备编码回归验证；不能把未来版本源码直接视作升级兼容保证。
