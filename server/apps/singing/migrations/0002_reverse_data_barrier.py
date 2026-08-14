from django.db import migrations
from django.db.migrations.exceptions import IrreversibleError


def guard_singing_downgrade(apps, schema_editor):
    SingingSession = apps.get_model("singing", "SingingSession")
    AnalysisTask = apps.get_model("analysis", "AnalysisTask")
    if (
        SingingSession.objects.using(schema_editor.connection.alias).exists()
        or AnalysisTask.objects.using(schema_editor.connection.alias).filter(
            target_type="singing_session",
        ).exists()
    ):
        raise IrreversibleError("演唱会话与通用演唱任务无法无损降级")


class Migration(migrations.Migration):
    dependencies = [("singing", "0001_initial")]

    operations = [
        migrations.RunPython(migrations.RunPython.noop, guard_singing_downgrade),
    ]
