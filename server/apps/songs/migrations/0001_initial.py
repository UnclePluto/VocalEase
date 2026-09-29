import uuid

from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):
    initial = True
    # 只依赖 MediaAsset 的初始表；不能让歌曲迁移成为媒体不可逆屏障之前
    # 的反向依赖，否则旧媒体 schema 的安全回滚检查会先改动其他 app。
    dependencies = [("media", "0001_initial")]
    operations = [
        migrations.CreateModel(
            name="Song",
            fields=[
                ("id", models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ("deleted_at", models.DateTimeField(blank=True, null=True)),
                ("title", models.CharField(max_length=200)), ("artist", models.CharField(max_length=200)),
                ("genre", models.CharField(max_length=64)), ("language", models.CharField(max_length=64)),
                ("duration_seconds", models.PositiveIntegerField()),
                ("analysis_status", models.CharField(choices=[("pending", "待分析"), ("processing", "分析中"), ("succeeded", "分析成功"), ("failed", "分析失败"), ("retrying", "重试中")], default="pending", max_length=16)),
                ("publication_status", models.CharField(choices=[("draft", "未发布"), ("published", "已发布")], default="draft", max_length=16)),
                ("created_at", models.DateTimeField(auto_now_add=True)), ("updated_at", models.DateTimeField(auto_now=True)),
                ("source_asset", models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.PROTECT, related_name="source_songs", to="media.mediaasset")),
            ],
            options={"ordering": ["-created_at"]},
        ),
        migrations.CreateModel(
            name="SongUploadIntent",
            fields=[
                ("id", models.UUIDField(primary_key=True, serialize=False)), ("song_id", models.UUIDField(unique=True)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                ("asset", models.OneToOneField(on_delete=django.db.models.deletion.PROTECT, related_name="song_upload_intent", to="media.mediaasset")),
            ],
        ),
        migrations.AddConstraint(model_name="song", constraint=models.CheckConstraint(condition=models.Q(("duration_seconds__gt", 0)), name="song_duration_positive")),
        migrations.AddConstraint(model_name="song", constraint=models.CheckConstraint(condition=models.Q(("analysis_status__in", ["pending", "processing", "succeeded", "failed", "retrying"])), name="song_analysis_status_valid")),
        migrations.AddConstraint(model_name="song", constraint=models.CheckConstraint(condition=models.Q(("publication_status__in", ["draft", "published"])), name="song_publication_status_valid")),
        migrations.AddConstraint(model_name="songuploadintent", constraint=models.CheckConstraint(condition=models.Q(("song_id", models.F("id"))), name="song_upload_intent_id_matches_song")),
    ]
