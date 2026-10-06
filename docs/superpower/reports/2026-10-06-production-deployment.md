# 线上发布与手机安装记录（2026-10-06）

用户已明确授权部署到线上并安装到手机。线上部署完成。发布当时手机安装等待解锁；后续已核对c19f66d实际安装包指纹及20:00:26更新时间。本轮又成功覆盖安装226f1c5，详见[后续修复验证](2026-10-06-singing-followup-verification.md)。

## 版本与工件

- 发布提交：`c19f66de2a250b0c4103e536936ada46bd739e79`。
- 部署标签：`deploy-20261006-singing-experience`。
- [构建与真实生产容器验证](https://github.com/UnclePluto/VocalEase/actions/runs/37454031082)：成功。
- [正式服务器发布](https://github.com/UnclePluto/VocalEase/actions/runs/37456002861)：成功。
- 签名 APK：`/Users/nick/.codex/worktrees/singing-experience/VocaEase/deploy/releases/c19f66de2a250b0c4103e536936ada46bd739e79/VocaEase-20261006-c19f66d.apk`。
- APK SHA256：`ca180db28098888dffe847def3eb8eef1f44ff2d122b87a702a8f7932920bc79`。
- 同目录保存固定四镜像摘要的 `release.tar` 和 `release-metadata.json`；不包含生产凭据。
- 手机：华为 LIO_AN00m，API31。使用与手机现有应用匹配的证书及 `adb install -r`；未卸载应用、未清除数据。后续已确认安装成品指纹匹配；最新226f1c5覆盖安装及启动结果见后续修复验证。

## 发布验证

- 最终线上 CI：服务端570通过、32跳过；后台183通过，类型、代码规范、构建通过。
- 四类固定摘要镜像完成真实 PostgreSQL 迁移、Django/Celery/Beat/Web 健康和代理 schema 检查。
- 正式服务器成功应用 songs0006–0008、singing0007/0008、media0010；发布脚本先备份生产配置与数据库，再停写迁移、启动并验收入口，成功版本为上述提交。
- 外网后台与两个域名的 schema 均返回200；新参考音高、伴奏核验、会话播放授权路由已出现；匿名患者接口返回401且为JSON。
- 线上后台：https://vocaease.whestsun.com/ 。线上API：https://vocaease-api.whestsun.com/ 。
- 公网 `/health/ready/` 被 SPA 路由处理，不能以其200视作就绪；实际就绪检查在发布脚本的内部服务端探针完成，外网使用 schema 与受保护接口检查。

## 本次发布准备中修复

- CI 未安装FFmpeg导致两项真实音频测试失败；补齐系统解码工具，最终全套通过。
- 后台 CI 复现空表瞬时元素被替换及 Ant Design Form 的10ms卸载回调访问已销毁window；等待当前DOM断言，并在jsdom销毁前排空回调，最终183项通过且没有未捕获错误。
- 从原工作区复制已存在的认证兼容修复到隔离分支，保留改密响应类型与固定诊断，时间统一使用OffsetDateTime。原工作区认证文件指纹未改变。
- 旧版安卓登录时间及新会话播放授权均完成修改前失败、修改后通过；API29隔离模拟器认证/时间共12项通过，完整安卓单测345项通过，lintDebug及构建通过。
- 正式HTTPS地址的Release严格依赖、lint、权限、无GMS、网络策略、隐私门禁通过；实际签名APK再扫隐私、核验17个保留接口响应类型、确认无模拟器地址。
- 类型检查器原正则跨过全限定名响应而误判；两项脚本执行回归完成失败到通过，真实压缩APK检查通过。

## 尚未完成

- 真实病例、整曲采音和首尾同步验收、七牛真实上传/CORS及跨设备医生回放仍未验证。
- 现有曲目仍需真实参考音高准备与原唱/伴奏起点核验；本次未自动伪造标注或改动生产曲目资料。

## 日志

`/tmp/vocaease-deploy-ci-final.log`、`/tmp/vocaease-deploy-production-final.log`、`/tmp/vocaease-deployment-external-health.json`、`/tmp/vocaease-deploy-release-final.log`、`/tmp/vocaease-deploy-auth-device-green.log`、`/tmp/vocaease-deploy-web-tests.log`。
