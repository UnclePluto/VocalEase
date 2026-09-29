from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("media", "0004_upload_lease")]
    operations = [
        migrations.AddField(model_name="mediaasset", name="upload_nonce", field=models.UUIDField(blank=True, null=True)),
        migrations.AddField(model_name="mediaasset", name="upload_lease_expires_at", field=models.DateTimeField(blank=True, null=True)),
        migrations.RemoveConstraint(model_name="mediaasset", name="media_asset_status_valid"),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=models.Q(("status__in", ["uploading", "receiving", "ready", "failed", "pending_cleanup"])), name="media_asset_status_valid")),
        migrations.AlterField(model_name="mediaasset", name="status", field=models.CharField(choices=[("uploading", "上传中"), ("receiving", "接收中"), ("ready", "可用"), ("failed", "失败"), ("pending_cleanup", "待清理")], default="uploading", max_length=24)),
    ]
