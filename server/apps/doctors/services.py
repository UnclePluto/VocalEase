import os
import time

from django.db import IntegrityError, OperationalError, transaction
from django.utils import timezone
from rest_framework.exceptions import APIException

from apps.accounts.models import Role, User
from apps.accounts.services import update_account_security_state
from apps.audit.services import record

from .models import DoctorProfile, SequenceCounter


class DoctorHasActivePatients(APIException):
    status_code = 409
    default_detail = "医生仍有在治患者，无法删除"
    default_code = "doctor_has_active_patients"


def next_sequence(prefix: str, width: int) -> str:
    """Issue a never-reused number while holding the persistent counter row lock."""
    with transaction.atomic():
        counter = SequenceCounter.objects.select_for_update().get(prefix=prefix)
        counter.value += 1
        counter.save(update_fields=["value"])
        return f"{prefix}{counter.value:0{width}d}"


def _postgres_sqlstate(exc) -> str | None:
    cause = getattr(exc, "__cause__", None)
    return getattr(cause, "sqlstate", None) or getattr(cause, "pgcode", None)


def _is_retryable_database_error(exc) -> bool:
    if transaction.get_connection().vendor == "postgresql":
        return _postgres_sqlstate(exc) in {"40001", "40P01", "55P03"}
    is_sqlite_test = (
        transaction.get_connection().vendor == "sqlite"
        and os.getenv("DJANGO_SETTINGS_MODULE") == "vocaease.settings.test"
    )
    return bool(
        is_sqlite_test
        and isinstance(exc, OperationalError)
        and exc.args == ("database is locked",)
    )


def run_with_database_retry(operation, *, attempts: int = 4):
    for attempt in range(attempts):
        try:
            return operation()
        except (IntegrityError, OperationalError) as exc:
            if not _is_retryable_database_error(exc) or attempt == attempts - 1:
                raise
            time.sleep(0.01 * (attempt + 1))
    raise RuntimeError("数据库操作重试失败")


def _create_doctor_once(*, name, gender, phone, department, title, actor, request_id):
    with transaction.atomic():
        employee_no = next_sequence("D", width=4)
        user = User.objects.create_user(login_id=employee_no, role=Role.DOCTOR, password="888888")
        doctor = DoctorProfile.objects.create(
            user=user,
            employee_no=employee_no,
            name=name,
            gender=gender,
            phone=phone,
            department=department,
            title=title,
        )
        record(
            actor=actor,
            action="doctor.create",
            target=doctor,
            changes={"employee_no": employee_no, "name": name, "gender": gender, "phone": phone, "department": department, "title": title},
            request_id=request_id,
        )
        return doctor


def create_doctor(*, name, gender, phone, department, title, actor=None, request_id=""):
    return run_with_database_retry(
        lambda: _create_doctor_once(
                name=name, gender=gender, phone=phone, department=department, title=title,
                actor=actor, request_id=request_id,
        )
    )


def update_doctor(*, actor, doctor, request_id: str, **changes):
    editable = {key: value for key, value in changes.items() if key in {"name", "gender", "phone", "department", "title"}}
    if not editable:
        return doctor
    with transaction.atomic():
        locked = DoctorProfile.objects.select_for_update().get(pk=doctor.pk, deleted_at__isnull=True)
        before = {key: getattr(locked, key) for key in editable}
        for key, value in editable.items():
            setattr(locked, key, value)
        locked.save(update_fields=[*editable.keys(), "updated_at"])
        record(actor=actor, action="doctor.update", target=locked, changes={key: {"from": before[key], "to": editable[key]} for key in editable}, request_id=request_id)
        return locked


def soft_delete_doctor(*, actor, doctor, request_id: str):
    from apps.patients.models import TreatmentPlan
    from apps.patients.models import PatientProfile

    with transaction.atomic():
        locked = DoctorProfile.objects.select_for_update().select_related("user").get(pk=doctor.pk, deleted_at__isnull=True)
        # Canonical order: DoctorProfile -> PatientProfile -> TreatmentPlan.
        patients = list(
            PatientProfile.objects.select_for_update()
            .filter(primary_doctor=locked, deleted_at__isnull=True)
            .order_by("id")
        )
        current_plans = list(
            TreatmentPlan.objects.select_for_update().filter(
                patient__in=patients,
                status__in=[TreatmentPlan.Status.PENDING, TreatmentPlan.Status.ACTIVE],
                deleted_at__isnull=True,
            ).order_by("id")
        )
        if any(
            plan.status == TreatmentPlan.Status.ACTIVE
            for plan in current_plans
        ):
            raise DoctorHasActivePatients()
        update_account_security_state(actor=actor, target=locked.user, is_active=False, deleted=True, request_id=request_id)
        locked.deleted_at = timezone.now()
        locked.save(update_fields=["deleted_at"])
        record(actor=actor, action="doctor.delete", target=locked, changes={"deleted": True}, request_id=request_id)
        return locked
