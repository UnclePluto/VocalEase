# VocaEase 患者 API 适配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox tracking and each task ends with a Chinese Git commit.

**Goal:** 在不破坏现有医生后台和患者 API 的前提下，为 Android 患者闭环补齐治疗进度、会话创建幂等、音视频双必传和可生成客户端模型的 OpenAPI 契约。

**Architecture:** 继续保留 Django/DRF 单体边界。患者首页进度由 `patients` selector 聚合，演唱状态和幂等规则仍集中在 `singing` service，视图只解析请求并映射 HTTP 状态。数据库用条件唯一约束兜底；OpenAPI 使用具名响应 serializer，不再让 Android 关键接口只暴露 `JSONField`。

**Tech Stack:** Python 3.13、Django 5、Django REST Framework、drf-spectacular、PostgreSQL/SQLite、pytest、uv。

**Spec:** `docs/superpower/specs/2026-08-26-vocaease-android-client-design.md`

## Global Constraints

- 实施目录为 `/Users/nick/my_dev/workout/VocaEase/.worktrees/vocaease-rebuild`，分支为 `codex/vocaease-rebuild`；下文路径均相对该工作树根目录。
- 开始前运行 `git status --short`，保留用户已有改动；不要在本计划内合并或重置其他分支。
- 每个外部写接口继续返回统一 `{code, message, data, request_id}` 信封。
- `Idempotency-Key` 区分患者作用域：同一患者同一键同一请求返回原资源，不同歌曲返回稳定的 `409 singing_creation_conflict`。
- 计划进度只统计当前活动治疗计划下、状态为 `completed` 且 `completed_at` 非空的会话。
- 治疗进度百分比使用两位小数字符串并封顶 `100.00`；当前周数限制在 `1..cycle_weeks`。
- 音频和视频都收到可信回执并确认后，会话才能进入 `uploaded` 并提交分析。
- 所有 Git 提交描述使用中文。

---

### Task 1: 为患者本人接口增加治疗进度快照

**Files:**

- Modify: `server/apps/patients/selectors.py`
- Modify: `server/apps/singing/serializers.py`
- Modify: `server/apps/singing/views.py`
- Test: `server/apps/singing/tests/test_patient_api.py`

**Consumes:** `PatientProfile`、活动 `TreatmentPlan`、`SingingSession.Status.COMPLETED`、`calculate_treatment_progress()`。

**Produces:** `GET /api/v1/patient/me/` 的可空 `treatment_progress`，以及供“我的”页使用的终身演唱汇总：

```json
{
  "completed_session_count": 8,
  "target_session_count": 24,
  "progress_percent": "33.33",
  "current_week": 3
}
```

```json
{
  "singing_summary": {
    "completed_session_count": 12,
    "total_duration_seconds": 3180
  }
}
```

- [ ] **Step 1: 写 selector 的失败测试**

在 `test_patient_api.py` 新增测试，构造当前计划内两条已完成会话、其他计划一条已完成会话和一条处理中会话，并冻结 selector 的 `today=date(2026, 8, 17)`。断言进度只计当前计划两次、目标取计划字段、百分比正确、周数为 3；终身汇总计三次已完成会话并累加其时长；另测无活动计划时进度为 `None` 但终身汇总仍存在。

```python
snapshot = patient_treatment_progress(patient=patient, today=date(2026, 8, 17))
assert snapshot == {
    "completed_session_count": 2,
    "target_session_count": patient.treatment_plans.get(status="active").target_session_count,
    "progress_percent": "16.67",
    "current_week": 3,
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `cd server && uv run pytest apps/singing/tests/test_patient_api.py -k treatment_progress -q`

Expected: FAIL，提示 `patient_treatment_progress` 尚不存在或响应缺少字段。

- [ ] **Step 3: 实现单查询聚合与周数计算**

在 `server/apps/patients/selectors.py` 增加：

```python
def patient_treatment_progress(*, patient: PatientProfile, today=None):
    today = today or timezone.localdate()
    plan = TreatmentPlan.objects.filter(
        patient=patient,
        status=TreatmentPlan.Status.ACTIVE,
        deleted_at__isnull=True,
    ).first()
    if plan is None:
        return None
    completed = SingingSession.objects.filter(
        patient=patient,
        treatment_plan=plan,
        status=SingingSession.Status.COMPLETED,
        completed_at__isnull=False,
    ).count()
    elapsed_week = ((today - plan.start_date).days // 7) + 1
    current_week = min(max(elapsed_week, 1), plan.cycle_weeks)
    progress = calculate_treatment_progress(completed, plan.target_session_count)
    return {
        "completed_session_count": completed,
        "target_session_count": plan.target_session_count,
        "progress_percent": format(progress, "f") if progress is not None else None,
        "current_week": current_week,
    }
```

同一 selector 文件增加 `patient_singing_summary(patient=patient)`，只聚合完成且有 `completed_at` 的患者会话：

```python
summary = SingingSession.objects.filter(
    patient=patient,
    status=SingingSession.Status.COMPLETED,
    completed_at__isnull=False,
).aggregate(
    completed_session_count=Count("id"),
    total_duration_seconds=Coalesce(Sum("duration_seconds"), 0),
)
```

为避免类型漂移，在 `singing/serializers.py` 增加 `PatientTreatmentProgressSerializer` 和 `PatientSingingSummarySerializer`。`PatientMeView` 调用 selector 并返回 `treatment_progress`、`singing_summary`；无活动计划时前者为 `null`，后者仍返回两个非负整数。

- [ ] **Step 4: 补 API 响应测试**

扩展现有 `test_patient_reads_profile_active_plan_and_only_own_sessions`，断言 `active_treatment_plan` 保持原结构，`treatment_progress` 和 `singing_summary` 同时存在。新增患者无活动计划测试，断言历史仍可读、进度为 `null`、汇总不受影响。

- [ ] **Step 5: 运行局部与 analytics 回归**

Run: `cd server && uv run pytest apps/singing/tests/test_patient_api.py apps/analytics/tests/test_calculations.py apps/analytics/tests/test_metrics_api.py -q`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add server/apps/patients/selectors.py server/apps/singing/serializers.py server/apps/singing/views.py server/apps/singing/tests/test_patient_api.py
git commit -m "补充患者治疗进度接口"
```

---

### Task 2: 在数据库和服务层实现会话创建幂等

**Files:**

- Modify: `server/apps/singing/models.py`
- Modify: `server/apps/singing/services.py`
- Create: `server/apps/singing/migrations/0005_singingsession_creation_idempotency.py`
- Test: `server/apps/singing/tests/test_submission_idempotency.py`
- Test: `server/apps/singing/tests/test_migration_safety.py`

**Consumes:** 患者 ID、歌曲 ID、`Idempotency-Key`、现有活动计划和歌曲可用性校验。

**Produces:** `SessionCreationResult(session, created)`；数据库保证非空键在同一患者内唯一。

- [ ] **Step 1: 写服务层失败测试**

新增三项测试：同键同歌曲只创建一次；同键不同歌曲抛出 `SingingCreationConflict`；129 字符或空键返回 DRF `ValidationError`。核心断言：

```python
first = create_session(
    patient_id=patient.id, song_id=song.id, idempotency_key="create-001"
)
second = create_session(
    patient_id=patient.id, song_id=song.id, idempotency_key="create-001"
)
assert first.created is True
assert second.created is False
assert first.session.id == second.session.id
```

- [ ] **Step 2: 运行并确认失败**

Run: `cd server && uv run pytest apps/singing/tests/test_submission_idempotency.py -k create_session -q`

Expected: FAIL，`create_session` 不接受 `idempotency_key` 或未返回 `created`。

- [ ] **Step 3: 增加字段与条件唯一约束**

在 `SingingSession` 增加：

```python
creation_idempotency_key = models.CharField(max_length=128, blank=True)
```

在 `Meta.constraints` 增加：

```python
models.UniqueConstraint(
    fields=["patient", "creation_idempotency_key"],
    condition=~Q(creation_idempotency_key=""),
    name="singing_session_creation_idempotency_unique",
)
```

用 `uv run python manage.py makemigrations singing --name singingsession_creation_idempotency` 生成 `0005`，检查 migration 只新增字段和约束。给 migration safety 测试补充正向/反向迁移检查以及重复非空键触发 `IntegrityError`。

- [ ] **Step 4: 实现服务返回值和冲突语义**

在 `services.py` 增加：

```python
class SingingCreationConflict(APIException):
    status_code = 409
    default_code = "singing_creation_conflict"
    default_detail = "幂等键已用于创建其他演唱会话"

@dataclass(frozen=True)
class SessionCreationResult:
    session: SingingSession
    created: bool
```

把 `create_session` 改为必须接收 `idempotency_key`。进入事务并锁定患者后，先按 `(patient_id, creation_idempotency_key)` 查找已有会话：歌曲相同则直接返回 `created=False`，歌曲不同则抛冲突；只有不存在时才校验计划和歌曲并创建。写入 `creation_idempotency_key` 后返回 `created=True`。

- [ ] **Step 5: 运行模型、迁移与服务测试**

Run: `cd server && uv run pytest apps/singing/tests/test_submission_idempotency.py apps/singing/tests/test_migration_safety.py -q`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add server/apps/singing/models.py server/apps/singing/services.py server/apps/singing/migrations/0005_singingsession_creation_idempotency.py server/apps/singing/tests/test_submission_idempotency.py server/apps/singing/tests/test_migration_safety.py
git commit -m "实现演唱会话创建幂等"
```

---

### Task 3: 将创建幂等暴露为 HTTP 契约并验证并发

**Files:**

- Modify: `server/apps/singing/views.py`
- Modify: `server/apps/singing/tests/test_patient_api.py`
- Modify: `server/apps/singing/tests/test_postgresql_concurrency.py`
- Modify: `server/tests/test_full_singing_flow.py`

**Consumes:** Task 2 的 `SessionCreationResult` 和 `SingingCreationConflict`。

**Produces:** 首次创建返回 201；相同键重放返回 200；缺失键返回 400；不同请求复用键返回 409。

- [ ] **Step 1: 写 HTTP 失败测试**

所有通过患者 API 创建会话的测试请求都添加稳定的 `HTTP_IDEMPOTENCY_KEY`。新增：

```python
first = client.post(url, payload, format="json", HTTP_IDEMPOTENCY_KEY="create-http-1")
replay = client.post(url, payload, format="json", HTTP_IDEMPOTENCY_KEY="create-http-1")
missing = client.post(url, payload, format="json")
assert first.status_code == 201
assert replay.status_code == 200
assert replay.json()["data"]["id"] == first.json()["data"]["id"]
assert missing.status_code == 400
```

- [ ] **Step 2: 运行并确认失败**

Run: `cd server && uv run pytest apps/singing/tests/test_patient_api.py tests/test_full_singing_flow.py -q`

Expected: FAIL，重放仍为 201 或缺少键仍被接受。

- [ ] **Step 3: 映射请求头和响应状态**

`PatientSessionListView.post()` 从 `request.headers["Idempotency-Key"]` 取值并传入 service；用 `result.created` 选择 201/200。不要把键加入 JSON body，也不要由服务端自动生成键。

- [ ] **Step 4: 增加 PostgreSQL 真并发测试**

沿用 `_session_lock_wrapper` 和 `ThreadPoolExecutor`，两个连接同时为同一患者、同一歌曲、同一键调用 `create_session()`；断言结果的 `created` 排序为 `[False, True]`、ID 相同、数据库只有一行。再用相同键并发请求不同歌曲，断言一个成功、一个 `singing_creation_conflict`，仍只有一个键绑定。

- [ ] **Step 5: 运行 SQLite 与 PostgreSQL 目标测试**

Run: `cd server && uv run pytest apps/singing/tests/test_patient_api.py tests/test_full_singing_flow.py -q`

Expected: PASS。

Run: `cd server && uv run pytest -m postgresql apps/singing/tests/test_postgresql_concurrency.py -q`

Expected: PostgreSQL 测试环境中 PASS；未配置 PostgreSQL 时明确 SKIP，不得假报通过。

- [ ] **Step 6: 提交**

```bash
git add server/apps/singing/views.py server/apps/singing/tests/test_patient_api.py server/apps/singing/tests/test_postgresql_concurrency.py server/tests/test_full_singing_flow.py
git commit -m "开放会话创建幂等接口"
```

---

### Task 4: 强制音频和视频均确认后才能提交

**Files:**

- Modify: `server/apps/singing/services.py`
- Modify: `server/apps/singing/tests/test_submission_idempotency.py`
- Modify: `server/apps/singing/tests/test_analysis_execution.py`
- Modify: `server/apps/singing/tests/test_upload_and_retry.py`
- Modify: `server/apps/singing/tests/test_patient_api.py`
- Modify: `server/apps/singing/tests/test_postgresql_concurrency.py`
- Modify: `server/apps/accounts/management/commands/seed_demo.py`
- Verify: `server/tests/test_full_singing_flow.py`

**Consumes:** `SessionMedia` 的 `singing_audio`、`singing_video` 绑定和可信 `MediaAsset.Status.READY` 回执。

**Produces:** 两种媒体均 ready+confirmed 才进入 `uploaded`；每代分析固定创建音频指标和人脸关键点两类任务。

- [ ] **Step 1: 写双必传失败测试**

把 `uploaded_session()` fixture 改为默认同时创建 ready+confirmed 的音频和视频。另建 `session_with_only_ready_audio()`，断言：

```python
with pytest.raises(SingingMediaConflict, match="演唱录像"):
    submit_session(
        session_id=session.id,
        patient_id=patient.id,
        idempotency_key="missing-video",
    )
```

补 API 测试：仅确认音频后状态仍是 `awaiting_upload`；确认视频后才是 `uploaded`。

- [ ] **Step 2: 运行并确认失败**

Run: `cd server && uv run pytest apps/singing/tests/test_submission_idempotency.py apps/singing/tests/test_upload_and_retry.py -k "video or confirm_upload" -q`

Expected: FAIL，现有实现仅音频即可 uploaded/submit。

- [ ] **Step 3: 收紧确认和提交状态机**

把 `confirm_session_media()` 的 ready 判断改为显式要求两种绑定：

```python
by_type = {item.media_type: item for item in bindings}
required_types = {"singing_audio", "singing_video"}
all_required_ready = all(
    media_type in by_type
    and by_type[media_type].confirmed_at
    and by_type[media_type].asset.status == MediaAsset.Status.READY
    for media_type in required_types
)
```

只有 `all_required_ready` 才保存 `status=UPLOADED`。`_locked_ready_bindings()` 分别给出“演唱音频尚未…”和“演唱录像尚未…”错误；`_create_generation_tasks_locked()` 不再条件添加视频任务，而是固定使用两个 asset。

- [ ] **Step 4: 更新共享 fixture、分析测试和演示数据**

删除测试中对 `_add_ready_video()` 的重复调用，所有正常提交断言两项任务。只在缺视频回归测试中使用 audio-only fixture。`seed_demo.py::_upsert_completed_session()` 同时发布并绑定 `video/mp4` 演示媒体，使 `seed_demo` 和 `qa_e2e` 不绕过业务规则。

- [ ] **Step 5: 运行演唱域和全流程回归**

Run: `cd server && uv run pytest apps/singing/tests tests/test_full_singing_flow.py tests/test_seed_demo.py tests/test_qa_e2e_command.py -q`

Expected: PASS；全流程创建 2 个当前代际分析任务。

- [ ] **Step 6: 提交**

```bash
git add server/apps/singing/services.py server/apps/singing/tests server/apps/accounts/management/commands/seed_demo.py server/tests/test_full_singing_flow.py
git commit -m "强制演唱音视频完整提交"
```

---

### Task 5: 允许未完成媒体在凭证过期后安全续签

**Files:**

- Modify: `server/apps/media/services.py`
- Verify: `server/apps/media/backends/local.py`
- Verify: `server/apps/media/backends/qiniu.py`
- Modify: `server/apps/singing/tests/test_upload_and_retry.py`
- Verify: `server/apps/media/tests/test_storage_contract.py`

**Consumes:** 状态为 `uploading` 的原 `MediaAsset`、原 object key、同一 session grant 幂等键和当前上传 TTL 配置。

**Produces:** 未过期重放保持原截止时间；已过期且尚未 ready 的媒体用同一 asset/object key 获得新的截止时间和 token，不创建第二个媒体绑定。

- [ ] **Step 1: 写过期续签失败测试**

把 asset 的 `upload_expires_at` 设为一分钟前，再用原 grant 幂等键调用 session upload-grants。断言 HTTP 200、asset ID/object key 不变、新 `expires_at` 晚于当前时间、`SessionMedia` 仍只有一行。另保留现有未过期重签保持 deadline 的断言。

```python
MediaAsset.objects.filter(pk=asset.id).update(
    upload_expires_at=timezone.now() - timedelta(minutes=1),
)
renewed = client.post(url, payload, format="json", HTTP_IDEMPOTENCY_KEY="grant-expired")
assert renewed.status_code == 200
assert renewed.json()["data"]["asset_id"] == str(asset.id)
assert renewed.json()["data"]["object_key"] == asset.object_key
```

- [ ] **Step 2: 运行并确认失败**

Run: `cd server && uv run pytest apps/singing/tests/test_upload_and_retry.py -k "expired and grant" -q`

Expected: FAIL，当前返回 `media_grant_expired`。

- [ ] **Step 3: 在事务中续签同一对象**

`reissue_upload_grant()` 对 deleted/非 uploading 仍拒绝。若原截止时间未来，继续沿用；若已过期，计算 `timezone.now() + timedelta(seconds=settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS)`，把新 deadline 传给 backend。backend 成功签发后再保存 `asset.upload_expires_at=grant.expires_at`。不得改变 object key、mime、size、owner 或 insert-only policy。

- [ ] **Step 4: 验证 local 和 Qiniu backend**

local 新签名仍绑定同一 asset/object/generation；Qiniu 新 policy 保持 `scope=private:{object_key}`、`insertOnly=1`、原 fsize/mime/callback，仅更新 deadline。补测试确认旧 token 不能越过服务端新的状态检查，ready/cancelled session 仍不可续签。

- [ ] **Step 5: 运行媒体和上传回归**

Run: `cd server && uv run pytest apps/singing/tests/test_upload_and_retry.py apps/media/tests/test_storage_contract.py apps/media/tests/test_security_regressions.py -q`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add server/apps/media/services.py server/apps/singing/tests/test_upload_and_retry.py
git commit -m "支持过期上传凭证安全续签"
```

---

### Task 6: 为 Android 关键接口提供具名 OpenAPI Schema

**Files:**

- Modify: `server/apps/accounts/schema.py`
- Modify: `server/apps/accounts/views.py`
- Create: `server/apps/singing/schema.py`
- Modify: `server/apps/singing/views.py`
- Modify: `server/apps/media/views.py`
- Modify: `server/apps/songs/views.py`
- Modify: `server/tests/test_openapi.py`

**Consumes:** 现有 request serializers、`SingingSessionReadSerializer`、`PatientTreatmentProgressSerializer`、统一 API 信封。

**Produces:** OpenAPI 组件明确描述 Android 登录/刷新、本人进度、歌曲分页/试听、会话创建/详情/状态、上传凭证、确认、提交、重试、结果和私有 URL。

- [ ] **Step 1: 写 Schema 失败断言**

在 `test_openapi.py` 增加 helper 解引用 OpenAPI `components.schemas` 中的 `$ref`，并断言：

```python
create = paths["/api/v1/patient/singing-sessions/"]["post"]
header = next(p for p in create["parameters"] if p["name"] == "Idempotency-Key")
assert header["in"] == "header" and header["required"] is True
me_schema = response_schema(paths["/api/v1/patient/me/"]["get"], "200")
assert {"treatment_progress", "singing_summary"} <= set(
    resolve_data_schema(me_schema)["properties"]
)
submit_schema = response_schema(
    paths["/api/v1/patient/singing-sessions/{session_id}/submit/"]["post"], "202"
)
assert set(resolve_data_schema(submit_schema)["properties"]) == {
    "session_id", "status", "analysis_task_ids"
}
```

同时断言登录响应 data 含 `access`、Android `refresh`、`user`；会话详情含 `media` 和 `analysis_results[].is_mock`。

- [ ] **Step 2: 运行并确认失败**

Run: `cd server && uv run pytest tests/test_openapi.py -q`

Expected: FAIL，关键响应仍指向泛型 `ApiEnvelope` 的 `JSONField`。

- [ ] **Step 3: 建立具名信封 serializers**

在 `accounts/schema.py` 增加 `LoginDataSerializer`、`RefreshDataSerializer` 及对应 envelope；在 `singing/schema.py` 增加：

```python
class SessionMutationDataSerializer(serializers.Serializer):
    session_id = serializers.UUIDField()
    status = serializers.ChoiceField(choices=SingingSession.Status.choices)
    analysis_task_ids = serializers.ListField(child=serializers.UUIDField())

class SessionMutationEnvelopeSerializer(ApiEnvelopeSerializer):
    data = SessionMutationDataSerializer()
```

同文件用具名 serializer 描述 PatientMe、创建/详情、分页、上传 grant 和试听/private URL 信封。复用现有读 serializer，避免复制会话字段第二份真相。

- [ ] **Step 4: 在视图声明参数和精确响应**

使用 `OpenApiParameter(name="Idempotency-Key", location=OpenApiParameter.HEADER, required=True, type=str)` 标注创建、提交和重试；上传凭证键标记为可选。把 Android 关键接口的 `responses` 从 `ApiEnvelopeSerializer` 替换为具名信封，并保留实际状态集合 200/201/202。

- [ ] **Step 5: 生成并检查 Schema**

Run: `cd server && uv run python manage.py spectacular --file /tmp/vocaease-openapi.yaml --validate`

Expected: 退出码 0，无 schema validation error。

Run: `cd server && uv run pytest tests/test_openapi.py apps/accounts/tests/test_auth_api.py apps/singing/tests/test_patient_api.py -q`

Expected: PASS。

- [ ] **Step 6: 跑服务端完整回归**

Run: `cd server && uv run pytest -q`

Expected: PASS；仅依赖外部 PostgreSQL/七牛配置的测试可按现有 marker 明确 SKIP。

- [ ] **Step 7: 提交**

```bash
git add server/apps/accounts/schema.py server/apps/accounts/views.py server/apps/singing/schema.py server/apps/singing/views.py server/apps/media/views.py server/apps/songs/views.py server/tests/test_openapi.py
git commit -m "完善安卓患者接口契约"
```

---

## Plan Completion Verification

- [ ] 运行 `cd server && uv run python manage.py makemigrations --check --dry-run`，Expected: `No changes detected`。
- [ ] 运行 `cd server && uv run python manage.py check --deploy`，Expected: 无新增 error；现有环境相关 warning 单独记录。
- [ ] 运行 `cd server && uv run pytest -q`，记录通过、跳过和失败数，不以局部测试代替完整结果。
- [ ] 检查 `git status --short`，确认只有计划内文件或已说明的用户改动。
- [ ] 检查 `git log -6 --oneline`，确认六个任务均以中文提交且顺序与计划一致。
