import uuid
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("media", "0003_media_asset_enums")]
    operations = [
        migrations.AddField(model_name="mediaasset", name="upload_nonce", field=models.UUIDField(blank=True, null=True)),
        migrations.AddField(model_name="mediaasset", name="upload_lease_expires_at", field=models.DateTimeField(blank=True, null=True)),
        migrations.RemoveConstraint(model_name="mediaasset", name="media_asset_status_valid"),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=models.Q(("status__in", ["uploading", "receiving", "ready", "failed", "pending_cleanup"])), name="media_asset_status_valid")),
    ]
