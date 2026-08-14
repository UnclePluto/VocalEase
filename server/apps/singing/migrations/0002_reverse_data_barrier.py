from django.db import migrations
from django.db.migrations.exceptions import IrreversibleError


LOCKED_TABLES = (
    "analysis_analysistask",
    "singing_analysistimeseries",
    "singing_sessionmedia",
    "singing_singingsession",
)


def lock_singing_write_surface(schema_editor):
    connection = schema_editor.connection
    if connection.vendor == "sqlite":
        return
    if connection.vendor != "postgresql":
        raise IrreversibleError("当前数据库不支持安全回退演唱会话")
    quoted = ", ".join(connection.ops.quote_name(table) for table in LOCKED_TABLES)
    with connection.cursor() as cursor:
        cursor.execute(f"LOCK TABLE {quoted} IN ACCESS EXCLUSIVE MODE")


def guard_singing_downgrade(apps, schema_editor):
    lock_singing_write_surface(schema_editor)
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
