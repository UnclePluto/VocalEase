# VocaEase 生产部署

生产服务器为 `171.43.135.71`，入口是现有 OpenResty；本项目不重启 Docker 或现有业务容器。GitHub 仓库为 `UnclePluto/VocalEase`，部署代码位于 `codex/production-deployment`。

## 发布链路

分支推送执行测试、构建并上传 ACR，不部署。显式推送 `deploy-*` 标签才执行服务器部署。四种镜像（Web、Django/Celery、PostgreSQL 17、Redis 8）均通过 ACR 拉取，以完整提交 SHA 标记，实际部署固定镜像摘要。GitHub 的 `vocaease-release-<SHA>` 工件包含配置和摘要，不包含生产密钥。

生产配置保存于服务器 `/home/motioncare/vocaease-production/.env`，权限 600。仓库仅保留示例。ACR 密码及专用 SSH 发布密钥存放 GitHub Actions Secrets；专用公钥的强制命令只接受本项目发布包，不提供交互 Shell。

## 隔离边界

- Compose 项目：`vocaease-prod`。
- Web 回环端口：`127.0.0.1:19080`；仅 Web 连接已有 `whest_Lan`，别名 `vocaease-web`。
- PostgreSQL、Redis 仅位于本项目 internal 网络，不发布端口；服务端与任务进程通过本项目 egress 网络访问七牛。
- 容器 CPU 上限合计 1.9 核，内存上限合计 2688MiB；资源不足时停止发布。
- 所有服务日志限制为 10MiB × 3；备份和镜像保留量需定期评估，禁止全局 prune。
- 媒体复用 MotionCare 的私有七牛空间 `motioncare`、域名 `https://cdn.whestsun.com`，所有新对象使用 `vocaease-production/` 前缀，不修改已有对象。
- 七牛华南上传来源 `https://up-z2.qiniup.com`，已加入 Web CSP；回调为 `https://vocaease-api.whestsun.com/api/v1/media/qiniu/callback/`。

## 首次上线与后续更新

发布前验证配置、摘要、磁盘、内存、端口及入口配置。拉取镜像后，仅启动本项目数据服务，备份数据库、环境文件及 VocaEase 入口。停止本项目应用写入进程，执行一次迁移，再启动应用并等待所有健康检查通过。验收成功才切换域名入口。

每次发布的备份位于 `/home/motioncare/vocaease-production/backups/<UTC时间>/`，成功版本由 `current` 符号链接记录。服务端使用 Gunicorn；探针模拟可信 HTTPS 代理，不依赖容器内部 443 端口。

## 故障与回滚

迁移失败保留现场和备份，不自动降级数据库。启动失败同样停止发布并保留日志。入口验证失败自动恢复之前的 VocaEase 入口配置。恢复到旧镜像前，必须确认数据库迁移与旧应用兼容；镜像回滚不等于数据库回滚。

兼容性确认后，使用上一成功版本目录内的 `compose.prod.yaml` 和 `images.env`，配合生产 `.env`，对固定项目 `vocaease-prod` 执行 `up -d --wait`。不要执行 `down -v`，不要修改其他项目的容器、网络或数据卷。数据库恢复仅在人工确认停写及影响范围后执行。

## HTTPS 保留要求

只替换 `/opt/service/openresty_ssl_conf.d/vocaease-probe.conf`，保留两个域名的精确匹配、`/.well-known/acme-challenge/` 路由及 `vocaease-cert/current` 证书路径。自动续期脚本在 `/home/motioncare/vocaease-https/`，每天 03:17 和 15:17 检查，无证书变化时不加载入口。

当前算法是项目已有的流程模拟器，生产部署不代表已接入真实分析算法。
