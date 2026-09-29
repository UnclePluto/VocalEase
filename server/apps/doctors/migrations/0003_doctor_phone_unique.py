import re

from django.db import migrations, models


PHONE_SEPARATORS = re.compile(r"[\s-]+")


def normalize_existing_doctor_phones(apps, schema_editor):
    DoctorProfile = apps.get_model("doctors", "DoctorProfile")
    normalized_rows = []
    by_phone = {}
    for doctor in DoctorProfile.objects.order_by("employee_no", "id").iterator():
        normalized = PHONE_SEPARATORS.sub("", doctor.phone.strip())
        normalized_rows.append((doctor.pk, normalized))
        by_phone.setdefault(normalized, []).append(doctor.employee_no)

    conflicts = [values for values in by_phone.values() if len(values) > 1]
    if conflicts:
        # 不输出完整手机号，避免迁移日志泄露历史身份信息。
        examples = [f"{values[0][:1]}***" for values in conflicts[:3]]
        raise RuntimeError(
            "医生手机号规范化后存在重复："
            f"冲突组数={len(conflicts)}，工号示例={','.join(examples)}"
        )

    for doctor_id, normalized in normalized_rows:
        DoctorProfile.objects.filter(pk=doctor_id).update(phone=normalized)


class Migration(migrations.Migration):
    dependencies = [("doctors", "0002_seed_sequence_counters")]

    operations = [
        migrations.RunPython(
            normalize_existing_doctor_phones, reverse_code=migrations.RunPython.noop
        ),
        migrations.AddConstraint(
            model_name="doctorprofile",
            constraint=models.UniqueConstraint(
                fields=("phone",), name="doctor_phone_global_unique"
            ),
        ),
    ]
