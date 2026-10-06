# 第三轮演唱体验修复验证（2026-10-06）

用户确认现有流程局部设计，沿用当前会话逐项实施、线上部署和手机覆盖安装授权。原主工作区认证修改保持不变。

## 根因与修改

- 真机复现开始失败：创建接口经正常刷新后返回200，会话已存在。严格解析回归实际报未知字段 `accompaniment_preview_available`；安卓 `PlaybackBindingDto` 补齐它及 `accompaniment_preview_asset_id`，保留 `ignoreUnknownKeys = false`。回归先失败后通过，不把成功创建误判为失败。
- 准备页统一小字号文字行高、缩短标题和歌词空态区，说明卡文本限制于可用宽度并保留内边距。原唱/伴奏胶囊视觉32dp、触控48dp；仍支持滚动、安全区域及固定开始按钮。
- 医生进入明细自动获取各媒体授权，无准备按钮，不自动播放。认证epoch、会话和资产隔离、失败重试继续保留。
- 删除嗳气快捷按钮、伴奏偏移输入及解释段落。患者录音仍为主时钟，人声＋伴奏仅额外播放真实歌曲伴奏；未知历史同步起点从本轨零点试听，不回填数据。
- 看板使用Waviz1.0.0真实 `Visualizer.layer` 引擎，Wave4与Mixed4图层参数与原包一致；使用 `AudioAnalyzer` 子类传入现有患者采样，不另创建音频源，不混入伴奏，不启动第二套RAF。图形保留画布宽高比。预设逐RAF绘制，指标更新保持50ms限频。

## 检查证据

- 安卓完整单元351项通过；创建响应回归已确认先失败后通过。
- 专用API29模拟器准备页7项通过，包含小屏大字体、固定开始按钮、滚动和权限状态。390×844截图已视觉检查：`/tmp/vocaease-round3-preparation.png`。
- 正式域名Release严格离线依赖、lint、构建、权限、无GMS、网络策略、隐私扫描、17接口压缩响应类型门禁通过；实际签名APK再次完成隐私与17响应类型检查。
- 后台完整185项通过，类型、lint和生产构建通过；自动加载及简化控件、真实预设输出、逐RAF绘制回归均已确认红绿。Waviz发布包缺少sourcemap引用的源文件产生第三方警告，无测试失败。
- 独立只读审查无剩余Critical/Important。Minor预设动画被旧20帧限频影响已修复并回归。
- 日志：`/tmp/vocaease-round3-dto-red.log`、`/tmp/vocaease-round3-dto-green.log`、`/tmp/vocaease-round3-release.log`、`/tmp/vocaease-round3-web-final.log`、`/tmp/vocaease-round3-layout.log`。

## 发布与真机

- 正式版本：`151cea63be4ff999691d46eb31a7be4d84117934`，发布标签 `deploy-20261006-singing-round3`。
- [云端构建及生产容器检查](https://github.com/UnclePluto/VocalEase/actions/runs/37475639451)全部成功；[正式部署](https://github.com/UnclePluto/VocalEase/actions/runs/37477132443)成功。服务器日志确认发布上述版本，迁移无待执行项。
- 外网后台页面、API schema及后台代理schema均200；匿名患者接口401，鉴权正常。
- 华为LIO_AN00m覆盖安装成功，保留原应用数据；手机最后更新时间2026-10-06 22:09:12。手机实际安装包拉回核对SHA256：`70cdc70c7c0fd530776ef4fc9c2bb737745a88bf4ba2b2001b17f694559b94b5`，与发布APK一致，证书与现有应用一致。
- 真机核对准备页排版与胶囊，点击开始成功进入录制页，实际录制计时达到6秒；随后关闭并取消测试录制，返回准备页，未提交演唱。截图：`/tmp/vocaease-round3-phone-preparation.png`。
- 线上浏览器确认进入历史明细自动加载音频、伴奏和录像，无准备阶段、嗳气快捷按钮、说明或偏移输入；默认人声，组合切换可用。真实患者录音播放时Wave4和Mixed4动态变化，录像静音同步；录音25.8秒自然结束后暂停。截图：`/tmp/vocaease-round3-doctor-online.jpg`。
- 完整发布工件保存在 `deploy/releases/151cea63be4ff999691d46eb31a7be4d84117934/`（忽略目录），包含APK、发布包、镜像摘要及发布元数据。

旧录音已收进外放歌曲时，回放切换不能去掉串音；本轮不伪造音轨、参考音高或分析结果。真实整曲与临床效果仍需人工验收。
