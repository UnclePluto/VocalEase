# 使用单仓库组织 Android、Web、API 与 AI Worker

一期采用单仓库：参与者端使用 Kotlin、Jetpack Compose、Media3、AudioRecord、Room、WorkManager 和 OkHttp；管理后台使用 React、TypeScript、Vite 和 Ant Design；业务服务端使用 Python、FastAPI、SQLAlchemy、Alembic 和 PostgreSQL；AI Worker 使用 Python 并通过 Redis 队列异步执行音频处理。本地开发通过 Docker Compose 启动服务依赖，媒体文件经统一存储接口写入服务端本地持久化目录，以便生产化时替换为 OSS。
