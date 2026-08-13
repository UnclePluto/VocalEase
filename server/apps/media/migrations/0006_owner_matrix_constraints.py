from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("media", "0005_receiving_status_choice")]
    operations = [
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=(models.Q(("owner_type", "patient"), ("patient_owner__isnull", False), ("owner_id", models.F("patient_owner_id"))) | ~models.Q(("owner_type", "patient"))), name="media_asset_patient_owner_consistent")),
        migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=(models.Q(("owner_type__in", ["song", "system", "export"]), ("patient_owner__isnull", True)) | models.Q(("owner_type", "patient"))), name="media_asset_nonpatient_owner_null")),
    ]
