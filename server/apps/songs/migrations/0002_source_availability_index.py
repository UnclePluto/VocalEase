from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("songs", "0001_initial")]
    operations = [
        migrations.AddField(model_name="song", name="source_available", field=models.BooleanField(default=False)),
        migrations.AddField(model_name="song", name="source_verified_at", field=models.DateTimeField(blank=True, null=True)),
        migrations.AddField(model_name="song", name="source_verified_asset_id", field=models.UUIDField(blank=True, null=True)),
        migrations.AddField(model_name="song", name="source_receipt_fingerprint", field=models.CharField(blank=True, max_length=64)),
        migrations.AddConstraint(
            model_name="song",
            constraint=models.CheckConstraint(
                condition=models.Q(models.Q(("source_asset__isnull", False), ("source_available", True), ("source_receipt_fingerprint__gt", ""), ("source_verified_asset_id", models.F("source_asset_id")), ("source_verified_asset_id__isnull", False), ("source_verified_at__isnull", False)), models.Q(("source_available", False), ("source_receipt_fingerprint", ""), ("source_verified_asset_id__isnull", True)), _connector="OR"),
                name="song_source_availability_valid",
            ),
        ),
    ]
