import uuid

from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):
    initial = True
    dependencies = [("media", "0001_initial"), ("songs", "0001_initial")]
    operations = [
        migrations.CreateModel(
            name="AnalysisTask",
            fields=[
                ("id", models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ("task_type", models.CharField(choices=[("vocal_separation", "人声分离"), ("accompaniment_generation", "伴奏生成"), ("lyrics_recognition", "歌词识别")], max_length=32)),
                ("protocol_version", models.CharField(default="1.0", max_length=16)), ("executor", models.CharField(default="mock_song", max_length=64)),
                ("status", models.CharField(choices=[("pending", "待处理"), ("processing", "处理中"), ("succeeded", "成功"), ("failed", "失败"), ("retrying", "重试中")], default="pending", max_length=16)),
                ("attempt", models.PositiveSmallIntegerField(default=0)), ("idempotency_key", models.CharField(max_length=128, unique=True)),
                ("input_snapshot", models.JSONField(default=dict)), ("result", models.JSONField(blank=True, default=dict)),
                ("error_code", models.CharField(blank=True, max_length=64)), ("error_summary", models.CharField(blank=True, max_length=256)),
                ("created_at", models.DateTimeField(auto_now_add=True)), ("started_at", models.DateTimeField(blank=True, null=True)), ("completed_at", models.DateTimeField(blank=True, null=True)), ("updated_at", models.DateTimeField(auto_now=True)),
                ("song", models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, related_name="analysis_tasks", to="songs.song")),
                ("source_asset", models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, related_name="analysis_tasks", to="media.mediaasset")),
            ], options={"ordering": ["-created_at"]},
        ),
        migrations.CreateModel(name="AnalysisResult", fields=[
            ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
            ("protocol_version", models.CharField(max_length=16)), ("is_mock", models.BooleanField(default=False)), ("payload", models.JSONField(default=dict)), ("created_at", models.DateTimeField(auto_now_add=True)),
            ("task", models.OneToOneField(on_delete=django.db.models.deletion.PROTECT, related_name="analysis_result", to="analysis.analysistask")),
        ]),
        migrations.AddConstraint(model_name="analysistask", constraint=models.CheckConstraint(condition=models.Q(("status__in", ["pending", "processing", "succeeded", "failed", "retrying"])), name="analysis_task_status_valid")),
        migrations.AddConstraint(model_name="analysistask", constraint=models.CheckConstraint(condition=models.Q(("attempt__gte", 0)), name="analysis_task_attempt_nonnegative")),
    ]
