from django.db import migrations
from django.db.migrations.exceptions import IrreversibleError


def reject_reverse(apps, schema_editor):
    raise IrreversibleError("媒体可信回执与通用所有权 schema 明确不可逆")


class Migration(migrations.Migration):
    dependencies = [("media", "0008_atomic_manifest_state")]
    operations = [migrations.RunPython(migrations.RunPython.noop, reject_reverse)]
