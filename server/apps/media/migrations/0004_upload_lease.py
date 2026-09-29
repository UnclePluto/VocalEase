import uuid
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("media", "0003_media_asset_enums")]
    operations = [
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=models.Q(("backend__in", ["local", "qiniu"])), name="media_asset_backend_valid")),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=models.Q(("owner_type__in", ["patient", "song", "system", "export"])), name="media_asset_owner_type_valid")),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=models.Q(("status__in", ["uploading", "ready", "failed", "pending_cleanup"])), name="media_asset_status_valid")),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=(models.Q(("backend", "local"), ("sha256__regex", "^[0-9a-f]{64}$"), ("status", "ready")) | models.Q(("backend", "qiniu"), ("etag__gt", ""), ("status", "ready")) | ~models.Q(("status", "ready"))), name="media_asset_ready_receipt_valid")),
    ]
