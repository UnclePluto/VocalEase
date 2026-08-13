from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("media", "0001_initial")]

    operations = [
        migrations.RemoveIndex(model_name="mediaasset", name="media_media_owner_i_f272bb_idx"),
        migrations.RenameField(model_name="mediaasset", old_name="owner", new_name="patient_owner"),
        migrations.AddField(model_name="mediaasset", name="owner_id", field=models.UUIDField(null=True)),
        migrations.AddField(model_name="mediaasset", name="etag", field=models.CharField(blank=True, max_length=128)),
        migrations.RunSQL("UPDATE media_mediaasset SET owner_id = patient_owner_id WHERE owner_id IS NULL", migrations.RunSQL.noop),
        migrations.AlterField(model_name="mediaasset", name="patient_owner", field=models.ForeignKey(blank=True, null=True, on_delete=models.PROTECT, related_name="media_assets", to="patients.patientprofile")),
        migrations.AlterField(model_name="mediaasset", name="owner_id", field=models.UUIDField()),
        migrations.AlterField(model_name="mediaasset", name="owner_type", field=models.CharField(choices=[("patient", "患者"), ("song", "歌曲"), ("system", "系统"), ("export", "导出")], max_length=16)),
        migrations.AddIndex(model_name="mediaasset", index=models.Index(fields=["patient_owner", "status"], name="media_asset_patient_status_idx")),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=models.Q(("backend__in", ["local", "qiniu"])), name="media_asset_backend_valid")),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=models.Q(("owner_type__in", ["patient", "song", "system", "export"])), name="media_asset_owner_type_valid")),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=models.Q(("status__in", ["uploading", "ready", "failed", "pending_cleanup"])), name="media_asset_status_valid")),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=(models.Q(("backend", "local"), ("sha256__regex", "^[0-9a-f]{64}$"), ("status", "ready")) | models.Q(("backend", "qiniu"), ("etag__gt", ""), ("status", "ready")) | ~models.Q(("status", "ready"))), name="media_asset_ready_receipt_valid")),
    ]
