# Task 2：演唱会话创建幂等报告

## 实现内容

- `SingingSession` 增加 `creation_idempotency_key`，并以患者和非空键建立条件唯一约束。
- `create_session` 现在强制要求幂等键，校验非空及最长 128 字符；返回不可变的 `SessionCreationResult(session, created)`。
- 同一患者同键同歌曲返回已有会话（`created=False`）；同键不同歌曲抛出 `SingingCreationConflict`（409）。
- 在锁定患者的事务内先查找已有创建，再进行活动计划、歌曲和源媒体校验并创建，写入创建幂等键。
- 患者会话创建视图在收到 `Idempotency-Key` 时原样传递；未收到时生成一次性服务端键，保持现有 HTTP 回归兼容，未提前实现 Task 3 的 HTTP 契约。
- 0005 在正向只新增字段和条件唯一约束；反向执行时增加安全屏障：空库允许回退，有演唱会话则在任何 schema 变更前拒绝回退。这是为保持既有全量回退屏障的原子性所必需。

## 修改文件

- `server/apps/singing/models.py`
- `server/apps/singing/services.py`
- `server/apps/singing/views.py`
- `server/apps/singing/migrations/0005_singingsession_creation_idempotency.py`
- `server/apps/singing/tests/test_submission_idempotency.py`
- `server/apps/singing/tests/test_migration_safety.py`

## TDD 证据

### RED

1. 先加入创建幂等行为测试后运行：

   ```bash
   cd server && uv run pytest apps/singing/tests/test_submission_idempotency.py -k create_session -q
   ```

   结果：测试收集失败，`ImportError: cannot import name 'SingingCreationConflict'`，证明冲突类型和新服务契约尚不存在。

2. 在补充迁移安全测试并运行相关回归后，既有反向屏障测试失败：0005 先移除了字段/约束，才触发 0004 的数据回退屏障，违反“失败前 schema 不变”的断言。该失败可稳定由以下命令复现：

   ```bash
   cd server && uv run pytest apps/singing/tests/test_migration_safety.py::test_singing_reverse_barrier_stops_before_schema_or_data_changes -q
   ```

### GREEN

1. 最小服务实现后，创建幂等聚焦测试：`4 passed, 5 deselected in 0.83s`。
2. 增加 0005 反向安全屏障并让历史迁移测试使用历史 ORM 模型后：

   ```bash
   cd server && uv run pytest apps/singing/tests/test_submission_idempotency.py apps/singing/tests/test_migration_safety.py apps/singing/tests/test_patient_api.py -q
   ```

   精确结果：`25 passed, 1 skipped in 3.57s`。

## 测试命令与结果

```bash
cd server && uv run python manage.py makemigrations --check --dry-run
```

结果：`No changes detected`。命令同时输出本机 PostgreSQL 未启动的迁移历史检查警告；不影响 Django 检测结果和 SQLite 测试数据库。

```bash
cd server && uv run pytest apps/singing/tests/test_submission_idempotency.py apps/singing/tests/test_migration_safety.py -q
```

结果：`14 passed, 1 skipped in 3.21s`。

```bash
cd server && uv run pytest apps/singing/tests/test_patient_api.py -q
```

结果：`11 passed in 1.11s`。

最终组合回归结果见上方：`25 passed, 1 skipped in 3.57s`。

## 自审与顾虑

- 已确认所有直接内部调用点仅为患者创建视图；该视图已适配新的必填服务参数，并保留统一响应信封。
- 条件唯一约束允许空键重复，服务层不接受空键；这满足数据库兼容及服务契约。
- 反向迁移对已有演唱会话会明确拒绝，避免发生不可逆的数据/schema 不一致；空数据库仍可正常回退。
- 未新增外部服务、GMS 依赖或 Task 3 的 HTTP 400/409/200/201 行为。
