from django.db import migrations


def seed_sequence_counters(apps, schema_editor):
    sequence_counter = apps.get_model("doctors", "SequenceCounter")
    for prefix in ("D", "P"):
        sequence_counter.objects.get_or_create(prefix=prefix, defaults={"value": 0})


class Migration(migrations.Migration):
    dependencies = [("doctors", "0001_initial")]

    operations = [
        migrations.RunPython(seed_sequence_counters, migrations.RunPython.noop),
    ]
