from django.db import migrations
from django.db.migrations.exceptions import IrreversibleError


def reject_reverse(apps, schema_editor):
    raise IrreversibleError("演唱绑定与参考音高依赖可信媒体 schema，禁止隐式回退")


class Migration(migrations.Migration):
    dependencies = [
        ("media", "0009_irreversible_schema_barrier"),
        ("singing", "0007_session_playback"),
    ]
    operations = [migrations.RunPython(migrations.RunPython.noop, reject_reverse)]
