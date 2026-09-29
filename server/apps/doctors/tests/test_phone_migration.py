import pytest
from django.db import connection
from django.db.migrations.executor import MigrationExecutor
from django.db.migrations.recorder import MigrationRecorder


@pytest.mark.django_db(transaction=True)
def test_phone_unique_migration_fails_deterministically_on_normalized_duplicates():
    executor = MigrationExecutor(connection)
    executor.migrate([("doctors", "0002_seed_sequence_counters")])
    old_apps = executor.loader.project_state(
        [("doctors", "0002_seed_sequence_counters")]
    ).apps
    User = old_apps.get_model("accounts", "User")
    Doctor = old_apps.get_model("doctors", "DoctorProfile")
    first_user = User.objects.create(login_id="migration-D1", role="doctor", password="x")
    second_user = User.objects.create(login_id="migration-D2", role="doctor", password="x")
    base = {
        "name": "迁移医生",
        "gender": "male",
        "department": "康复科",
        "title": "医师",
    }
    Doctor.objects.create(user=first_user, employee_no="DM001", phone="138 0000 0001", **base)
    Doctor.objects.create(user=second_user, employee_no="DM002", phone="13800000001", **base)

    with pytest.raises(RuntimeError, match="医生手机号规范化后存在重复"):
        MigrationExecutor(connection).migrate([("doctors", "0003_doctor_phone_unique")])

    assert not MigrationRecorder(connection).migration_qs.filter(
        app="doctors", name="0003_doctor_phone_unique"
    ).exists()
    assert list(Doctor.objects.order_by("employee_no").values_list("phone", flat=True)) == [
        "138 0000 0001",
        "13800000001",
    ]
    with connection.cursor() as cursor:
        constraints = connection.introspection.get_constraints(
            cursor, Doctor._meta.db_table
        )
    assert "doctor_phone_global_unique" not in constraints

    Doctor.objects.filter(employee_no="DM002").delete()
    User.objects.filter(pk=second_user.pk).delete()
    MigrationExecutor(connection).migrate(
        MigrationExecutor(connection).loader.graph.leaf_nodes()
    )


@pytest.mark.django_db(transaction=True)
def test_phone_unique_migration_normalizes_existing_non_duplicate_values():
    executor = MigrationExecutor(connection)
    executor.migrate([("doctors", "0002_seed_sequence_counters")])
    old_apps = executor.loader.project_state(
        [("doctors", "0002_seed_sequence_counters")]
    ).apps
    User = old_apps.get_model("accounts", "User")
    Doctor = old_apps.get_model("doctors", "DoctorProfile")
    user = User.objects.create(login_id="migration-D3", role="doctor", password="x")
    doctor = Doctor.objects.create(
        user=user,
        employee_no="DM003",
        name="规范化医生",
        gender="female",
        phone=" 139-0000-0002 ",
        department="康复科",
        title="医师",
    )

    MigrationExecutor(connection).migrate([("doctors", "0003_doctor_phone_unique")])
    new_apps = MigrationExecutor(connection).loader.project_state(
        [("doctors", "0003_doctor_phone_unique")]
    ).apps

    assert new_apps.get_model("doctors", "DoctorProfile").objects.get(
        pk=doctor.pk
    ).phone == "13900000002"

    MigrationExecutor(connection).migrate(
        MigrationExecutor(connection).loader.graph.leaf_nodes()
    )
