# Android 隐私验收清单

执行命令前使用测试账号与可清理设备；输出只保留状态和 ID 后 8 位，不复制 token、完整私有 URL、手机号、备注或本地媒体路径。

- [ ] 日志：运行闭环后导出仅本应用相关日志，确认没有密码、JWT、refresh/upload token、完整 URL、患者信息和明文路径。
- [ ] Room：最新 schema 只保存账户作用域、会话/草稿/资产标识、摘要、状态、代际、deadline 和加密相对路径；不保存永久私有 URL、token 或明文媒体路径。
- [ ] WorkData：上传、分析、清理和撤销任务只携带 hash、草稿/session 标识、generation 或不可逆 slot id。
- [ ] 私有 URL：只在播放器内存使用；401/403 最多刷新一次；进程结束后不可从 Room/WorkData 找回完整 URL。
- [ ] 媒体密文：主文件前四字节为 `VEF1`，文件权限为 `0600`；不得输出文件正文或绝对路径。
- [ ] 删除：成功 submit 后双媒体密文删除；未提交草稿按 7 天策略；“退出并删除”覆盖 recordings、upload lease、七牛 checkpoint 和损坏 sidecar。
- [ ] 跨账户：A→B→A 后旧 lease/incarnation 不能读取、播放、续传或删除新会话数据。

提交候选构建必须执行：

```bash
./scripts/test_privacy_scanner.sh
./scripts/scan_release_privacy.sh \
  app/src/main/java app/build/outputs/apk/release/app-release-unsigned.apk
```

扫描器对生产日志仅 allowlist `NetworkModule` 中的脱敏 `NetworkDiagnostic` 出口；WorkManager `Data.Builder` / `workDataOf` 仅 allowlist 已审计的 hash、opaque id、代际证明和进度表达式。它同时扫 Kotlin/Java import、fully-qualified 引用、别名、方法引用和调用点，并解包 APK 逐项扫描 DEX、resources、native 库等内容，拒绝 JWT/Bearer、密码或 token JSON、带签名查询的 URL、私有对象键及绝对媒体路径。17 类稳定负例分别覆盖 Kotlin/Java 日志与 WorkData 别名、stdout 方法引用、allowlist 文件内变量转存/方法引用/扩展方法，以及 APK 内 Bearer、JWT、密码 JSON、签名 URL、对象键和绝对路径；扫描器自身错误不能被当作负例通过。

安全执行示例（root 测试模拟器；不要在患者设备执行）：

```bash
adb -s emulator-5554 shell run-as com.vocaease.patient \
  find files -type f -exec stat -c '%a:%n' '{}' +
adb -s emulator-5554 shell run-as com.vocaease.patient sh -c \
  'f=$(find files -name "*.vef" -type f | head -1); test -n "$f" && od -An -tx1 -N4 "$f"'
adb -s emulator-5554 logcat -d -v brief | rg 'VocaEase|com\.vocaease\.patient'
```

期望头字节为 `56 45 46 31`，权限为 `600`。命令输出如包含测试标识也只存后 8 位；验收后清除 logcat、卸载测试包或用应用数据清除功能删除全部测试数据。

上述 `find ... -exec stat` 形式已在 API29 `emulator-5556` 与 API34 `emulator-5554` 的 toybox 环境执行；两台均返回命令状态 0。它替代 Android toybox 不支持的 GNU `find -printf`。
