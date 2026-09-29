from django.db import migrations, models
import django.db.models.deletion


def move_payloads_and_release_legacy_processing(apps, schema_editor):
    Task = apps.get_model("analysis", "AnalysisTask")
    Result = apps.get_model("analysis", "AnalysisResult")
    for task in Task.objects.exclude(result={}):
        payload = task.result if isinstance(task.result, dict) else {}
        if payload:
            Result.objects.update_or_create(
                task_id=task.id,
                defaults={
                    "protocol_version": str(payload.get("protocol_version", task.protocol_version)),
                    "is_mock": bool(payload.get("is_mock", False)),
                    "payload": payload,
                },
            )
    Task.objects.filter(status="processing").update(status="retrying")


class Migration(migrations.Migration):
    dependencies = [("analysis", "0001_initial"), ("songs", "0002_source_availability_index")]
    operations = [
        migrations.AddField(model_name="analysistask", name="claim_token", field=models.UUIDField(blank=True, null=True)),
        migrations.AddField(model_name="analysistask", name="lease_expires_at", field=models.DateTimeField(blank=True, null=True)),
        migrations.AddField(model_name="analysistask", name="heartbeat_at", field=models.DateTimeField(blank=True, null=True)),
        migrations.AlterField(model_name="analysisresult", name="task", field=models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name="analysis_result", to="analysis.analysistask")),
        migrations.RunPython(move_payloads_and_release_legacy_processing, migrations.RunPython.noop),
        migrations.RemoveField(model_name="analysistask", name="result"),
        migrations.RemoveConstraint(model_name="analysistask", name="analysis_task_status_valid"),
        migrations.AlterField(model_name="analysistask", name="status", field=models.CharField(choices=[("pending", "待处理"), ("processing", "处理中"), ("succeeded", "成功"), ("failed", "失败"), ("retrying", "重试中"), ("superseded", "已被新源替代")], default="pending", max_length=16)),
        migrations.AddConstraint(model_name="analysistask", constraint=models.CheckConstraint(condition=models.Q(("status__in", ["pending", "processing", "succeeded", "failed", "retrying", "superseded"])), name="analysis_task_status_valid")),
        migrations.AddConstraint(model_name="analysistask", constraint=models.CheckConstraint(condition=models.Q(("task_type__in", ["vocal_separation", "accompaniment_generation", "lyrics_recognition"])), name="analysis_task_type_valid")),
        migrations.AddConstraint(model_name="analysistask", constraint=models.CheckConstraint(condition=models.Q(("executor", "mock_song")), name="analysis_executor_valid")),
        migrations.AddConstraint(model_name="analysistask", constraint=models.CheckConstraint(condition=models.Q(("protocol_version", "1.0")), name="analysis_protocol_valid")),
        migrations.AddConstraint(
            model_name="analysistask",
            constraint=models.CheckConstraint(
                condition=models.Q(models.Q(("claim_token__isnull", False), ("heartbeat_at__isnull", False), ("lease_expires_at__isnull", False), ("status", "processing")), models.Q(models.Q(("status", "processing"), _negated=True), ("claim_token__isnull", True), ("heartbeat_at__isnull", True), ("lease_expires_at__isnull", True)), _connector="OR"),
                name="analysis_claim_lease_valid",
            ),
        ),
    ]
