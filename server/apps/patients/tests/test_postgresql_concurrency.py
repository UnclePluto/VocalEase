from concurrent.futures import ThreadPoolExecutor
from queue import Queue
from threading import Barrier, Event
from time import monotonic, sleep

import pytest
from django.db import connection, connections, transaction
from rest_framework.exceptions import ValidationError

from apps.accounts.models import Role, User
from apps.doctors.models import DoctorProfile
from apps.doctors.services import create_doctor, set_doctor_active, soft_delete_doctor
from apps.patients.models import PatientProfile, TreatmentPlan
from apps.patients.services import (
    create_patient,
    transition_treatment_plan_status,
    update_patient,
)


pytestmark = [pytest.mark.django_db(transaction=True), pytest.mark.postgresql]
LOCK_TIMEOUT_SECONDS = 5


@pytest.fixture(autouse=True)
def require_postgresql():
    if connection.vendor != "postgresql":
        pytest.skip("仅用于真实 PostgreSQL 行锁验证")
    from apps.doctors.models import SequenceCounter

    SequenceCounter.objects.bulk_create(
        [SequenceCounter(prefix="D"), SequenceCounter(prefix="P")],
        ignore_conflicts=True,
    )


@pytest.fixture
def admin_user():
    return User.objects.create_user(
        login_id="pg-race-admin", password="888888", role=Role.SYSTEM_ADMIN,
        must_change_password=False,
    )


def _doctor(index):
    return create_doctor(
        name=f"竞态医生{index}", gender="male", phone=f"13890000{index:03d}",
        department="康复科", title="医师",
    )


def _current_backend_pid():
    with connection.cursor() as cursor:
        cursor.execute("SELECT pg_backend_pid()")
        return cursor.fetchone()[0]


def _wait_until_backend_is_lock_waiting(pid):
    deadline = monotonic() + LOCK_TIMEOUT_SECONDS
    while monotonic() < deadline:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT wait_event_type FROM pg_stat_activity WHERE pid = %s",
                [pid],
            )
            row = cursor.fetchone()
        if row and row[0] == "Lock":
            return
        sleep(0.02)
    pytest.fail(f"PostgreSQL backend {pid} 未在 {LOCK_TIMEOUT_SECONDS} 秒内进入锁等待")


def test_postgresql_sequence_rows_are_preseeded_and_concurrent_numbers_are_unique():
    from apps.doctors.models import SequenceCounter

    assert set(SequenceCounter.objects.values_list("prefix", flat=True)) == {"D", "P"}

    def create_numbered_doctor(index):
        try:
            return _doctor(index).employee_no
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=4) as executor:
        numbers = list(executor.map(create_numbered_doctor, range(1, 5)))

    assert sorted(numbers) == ["D0001", "D0002", "D0003", "D0004"]

    doctors = list(DoctorProfile.objects.order_by("employee_no"))
    start_barrier = Barrier(5)
    contender_pids = Queue(maxsize=4)

    def create_numbered_patient(item):
        try:
            contender_pids.put(_current_backend_pid(), timeout=LOCK_TIMEOUT_SECONDS)
            start_barrier.wait(timeout=LOCK_TIMEOUT_SECONDS)
            return create_patient(
                name=f"编号患者{item[0]}", gender="female", enrollment_age=20 + item[0],
                phone=f"13780000{item[0]:03d}", doctor=item[1],
                start_date="2026-01-01", cycle_weeks=1,
            ).medical_record_no
        finally:
            connections.close_all()

    executor = ThreadPoolExecutor(max_workers=4)
    futures = [executor.submit(create_numbered_patient, item) for item in enumerate(doctors, start=1)]
    try:
        with transaction.atomic():
            from apps.doctors.models import SequenceCounter

            SequenceCounter.objects.select_for_update().get(prefix="P")
            start_barrier.wait(timeout=LOCK_TIMEOUT_SECONDS)
            pids = [contender_pids.get(timeout=LOCK_TIMEOUT_SECONDS) for _ in range(4)]
            for pid in pids:
                _wait_until_backend_is_lock_waiting(pid)
        patient_numbers = [future.result(timeout=LOCK_TIMEOUT_SECONDS) for future in futures]
    finally:
        executor.shutdown(wait=True, cancel_futures=True)

    assert sorted(patient_numbers) == ["P000001", "P000002", "P000003", "P000004"]


def test_doctor_delete_locks_pending_patient_before_inspecting_plans(monkeypatch, admin_user):
    doctor = _doctor(1)
    patient = create_patient(
        name="待开始患者", gender="female", enrollment_age=30, phone="13790000004",
        doctor=doctor, start_date="2026-01-01", cycle_weeks=4,
    )
    patient_locked = Event()
    allow_patient_unlock = Event()
    contender_pid = Queue(maxsize=1)

    def hold_patient_lock():
        try:
            with transaction.atomic():
                PatientProfile.objects.select_for_update().get(pk=patient.pk)
                patient_locked.set()
                assert allow_patient_unlock.wait(timeout=LOCK_TIMEOUT_SECONDS)
        finally:
            connections.close_all()

    def delete_doctor():
        try:
            contender_pid.put(_current_backend_pid(), timeout=LOCK_TIMEOUT_SECONDS)
            return soft_delete_doctor(
                actor=admin_user,
                doctor=doctor,
                request_id="pg-doctor-delete-lock-order",
            )
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as executor:
        holding = executor.submit(hold_patient_lock)
        assert patient_locked.wait(timeout=LOCK_TIMEOUT_SECONDS)
        deleting = executor.submit(delete_doctor)
        _wait_until_backend_is_lock_waiting(contender_pid.get(timeout=LOCK_TIMEOUT_SECONDS))
        allow_patient_unlock.set()
        holding.result(timeout=LOCK_TIMEOUT_SECONDS)
        deleting.result(timeout=LOCK_TIMEOUT_SECONDS)

    doctor.refresh_from_db()
    assert doctor.deleted_at is not None


def test_patient_creation_waits_for_doctor_delete_and_rejects_deleted_doctor(monkeypatch, admin_user):
    doctor = _doctor(1)
    delete_locked = Event()
    allow_delete = Event()
    contender_pid = Queue(maxsize=1)
    original = __import__("apps.doctors.services", fromlist=["update_account_security_state"]).update_account_security_state

    def pause_after_doctor_lock(**kwargs):
        delete_locked.set()
        assert allow_delete.wait(timeout=LOCK_TIMEOUT_SECONDS)
        return original(**kwargs)

    monkeypatch.setattr("apps.doctors.services.update_account_security_state", pause_after_doctor_lock)

    def delete():
        try:
            soft_delete_doctor(actor=admin_user, doctor=doctor, request_id="pg-delete-create-race")
        finally:
            connections.close_all()

    def create():
        try:
            contender_pid.put(_current_backend_pid(), timeout=LOCK_TIMEOUT_SECONDS)
            return create_patient(
                name="竞态患者", gender="female", enrollment_age=30, phone="13790000001",
                doctor=doctor, start_date="2026-01-01", cycle_weeks=4,
            )
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as executor:
        deleting = executor.submit(delete)
        assert delete_locked.wait(timeout=LOCK_TIMEOUT_SECONDS)
        creating = executor.submit(create)
        _wait_until_backend_is_lock_waiting(contender_pid.get(timeout=LOCK_TIMEOUT_SECONDS))
        allow_delete.set()
        deleting.result(timeout=LOCK_TIMEOUT_SECONDS)
        with pytest.raises(ValidationError) as exc_info:
            creating.result(timeout=LOCK_TIMEOUT_SECONDS)

    assert exc_info.value.get_codes()["primary_doctor"] == "invalid"
    assert not PatientProfile.objects.filter(primary_doctor=doctor).exists()


def test_patient_update_waits_for_doctor_delete_and_keeps_original_doctor(monkeypatch, admin_user):
    original_doctor = _doctor(1)
    deleting_doctor = _doctor(2)
    patient = create_patient(
        name="既有患者", gender="female", enrollment_age=30, phone="13790000002",
        doctor=original_doctor, start_date="2026-01-01", cycle_weeks=4,
    )
    delete_locked = Event()
    allow_delete = Event()
    contender_pid = Queue(maxsize=1)
    original = __import__("apps.doctors.services", fromlist=["update_account_security_state"]).update_account_security_state

    def pause_after_doctor_lock(**kwargs):
        delete_locked.set()
        assert allow_delete.wait(timeout=LOCK_TIMEOUT_SECONDS)
        return original(**kwargs)

    monkeypatch.setattr("apps.doctors.services.update_account_security_state", pause_after_doctor_lock)

    def delete():
        try:
            return soft_delete_doctor(
                actor=admin_user, doctor=deleting_doctor, request_id="pg-delete-update-race"
            )
        finally:
            connections.close_all()

    def update():
        try:
            contender_pid.put(_current_backend_pid(), timeout=LOCK_TIMEOUT_SECONDS)
            return update_patient(
                actor=admin_user,
                patient=patient,
                request_id="pg-patient-update-race",
                primary_doctor=deleting_doctor,
            )
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as executor:
        deleting = executor.submit(delete)
        assert delete_locked.wait(timeout=LOCK_TIMEOUT_SECONDS)
        updating = executor.submit(update)
        _wait_until_backend_is_lock_waiting(contender_pid.get(timeout=LOCK_TIMEOUT_SECONDS))
        allow_delete.set()
        deleting.result(timeout=LOCK_TIMEOUT_SECONDS)
        with pytest.raises(ValidationError) as exc_info:
            updating.result(timeout=LOCK_TIMEOUT_SECONDS)

    assert exc_info.value.get_codes()["primary_doctor"] == "invalid"
    patient.refresh_from_db()
    assert patient.primary_doctor_id == original_doctor.id


def test_plan_activation_and_doctor_delete_preserve_final_invariant(monkeypatch, admin_user):
    doctor = _doctor(1)
    patient = create_patient(
        name="待激活患者", gender="female", enrollment_age=30, phone="13790000003",
        doctor=doctor, start_date="2026-01-01", cycle_weeks=4,
    )
    plan = patient.treatment_plans.get()
    delete_locked = Event()
    allow_delete = Event()
    contender_pid = Queue(maxsize=1)
    original = __import__("apps.doctors.services", fromlist=["update_account_security_state"]).update_account_security_state

    def pause_after_doctor_lock(**kwargs):
        delete_locked.set()
        assert allow_delete.wait(timeout=LOCK_TIMEOUT_SECONDS)
        return original(**kwargs)

    monkeypatch.setattr("apps.doctors.services.update_account_security_state", pause_after_doctor_lock)

    def delete():
        try:
            return soft_delete_doctor(
                actor=admin_user, doctor=doctor, request_id="pg-delete-activate-race"
            )
        finally:
            connections.close_all()

    def activate():
        try:
            contender_pid.put(_current_backend_pid(), timeout=LOCK_TIMEOUT_SECONDS)
            return transition_treatment_plan_status(
                actor=admin_user,
                plan=plan,
                status=TreatmentPlan.Status.ACTIVE,
                request_id="pg-activate-delete-race",
            )
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as executor:
        deleting = executor.submit(delete)
        assert delete_locked.wait(timeout=LOCK_TIMEOUT_SECONDS)
        activating = executor.submit(activate)
        _wait_until_backend_is_lock_waiting(contender_pid.get(timeout=LOCK_TIMEOUT_SECONDS))
        allow_delete.set()
        deleting.result(timeout=LOCK_TIMEOUT_SECONDS)
        with pytest.raises(ValidationError) as exc_info:
            activating.result(timeout=LOCK_TIMEOUT_SECONDS)

    assert exc_info.value.get_codes()["primary_doctor"] == "invalid"
    doctor.refresh_from_db()
    plan.refresh_from_db()
    assert doctor.deleted_at is not None
    assert not TreatmentPlan.objects.filter(
        patient__primary_doctor=doctor,
        status=TreatmentPlan.Status.ACTIVE,
        deleted_at__isnull=True,
    ).exists()


def test_plan_activation_waits_for_doctor_deactivation_and_preserves_final_invariant(
    monkeypatch, admin_user
):
    doctor = _doctor(1)
    patient = create_patient(
        name="停用竞态患者",
        gender="female",
        enrollment_age=30,
        phone="13790000005",
        doctor=doctor,
        start_date="2026-01-01",
        cycle_weeks=4,
    )
    plan = patient.treatment_plans.get()
    status_locked = Event()
    allow_status_change = Event()
    contender_pid = Queue(maxsize=1)
    original = __import__(
        "apps.doctors.services", fromlist=["update_account_security_state"]
    ).update_account_security_state

    def pause_after_canonical_locks(**kwargs):
        status_locked.set()
        assert allow_status_change.wait(timeout=LOCK_TIMEOUT_SECONDS)
        return original(**kwargs)

    monkeypatch.setattr(
        "apps.doctors.services.update_account_security_state",
        pause_after_canonical_locks,
    )

    def deactivate():
        try:
            return set_doctor_active(
                actor=admin_user,
                doctor=doctor,
                is_active=False,
                request_id="pg-deactivate-activate-race",
            )
        finally:
            connections.close_all()

    def activate_plan():
        try:
            contender_pid.put(_current_backend_pid(), timeout=LOCK_TIMEOUT_SECONDS)
            return transition_treatment_plan_status(
                actor=admin_user,
                plan=plan,
                status=TreatmentPlan.Status.ACTIVE,
                request_id="pg-plan-activate-deactivate-race",
            )
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as executor:
        deactivating = executor.submit(deactivate)
        assert status_locked.wait(timeout=LOCK_TIMEOUT_SECONDS)
        activating = executor.submit(activate_plan)
        _wait_until_backend_is_lock_waiting(
            contender_pid.get(timeout=LOCK_TIMEOUT_SECONDS)
        )
        allow_status_change.set()
        deactivating.result(timeout=LOCK_TIMEOUT_SECONDS)
        with pytest.raises(ValidationError) as exc_info:
            activating.result(timeout=LOCK_TIMEOUT_SECONDS)

    assert exc_info.value.get_codes()["primary_doctor"] == "invalid"
    doctor.user.refresh_from_db()
    plan.refresh_from_db()
    assert doctor.user.is_active is False
    assert plan.status == TreatmentPlan.Status.PENDING
