import os
import time

from django.db import IntegrityError, OperationalError, transaction
from django.utils import timezone
from rest_framework.exceptions import APIException, NotFound, PermissionDenied, ValidationError

from apps.accounts.models import Role, User
from apps.accounts.services import update_account_security_state
from apps.audit.services import record
from common.privacy import normalize_phone

from .models import DoctorProfile, SequenceCounter


class DoctorHasActivePatients(APIException):
    status_code = 409
    default_detail = "医生仍有在治患者，无法删除"
    default_code = "doctor_has_active_patients"

    def __init__(self, *, action: str = "delete"):
        detail = (
            "该医生仍有进行中的患者，不能停用"
            if action == "deactivate"
            else self.default_detail
        )
        super().__init__(detail=detail, code=self.default_code)


def _lock_current_patient_plans(doctor):
    from apps.patients.models import PatientProfile, TreatmentPlan

    # Canonical order: DoctorProfile -> PatientProfile -> TreatmentPlan -> User.
    patients = list(
        PatientProfile.objects.select_for_update()
        .filter(primary_doctor=doctor, deleted_at__isnull=True)
        .order_by("id")
    )
    return list(
        TreatmentPlan.objects.select_for_update()
        .filter(
            patient__in=patients,
            status__in=[TreatmentPlan.Status.PENDING, TreatmentPlan.Status.ACTIVE],
            deleted_at__isnull=True,
        )
        .order_by("id")
    )


def _ensure_no_active_patients(doctor, *, action: str = "delete"):
    from apps.patients.models import TreatmentPlan

    if any(
        plan.status == TreatmentPlan.Status.ACTIVE
        for plan in _lock_current_patient_plans(doctor)
    ):
        raise DoctorHasActivePatients(action=action)


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


def _is_phone_unique_error(exc: IntegrityError) -> bool:
    cause = getattr(exc, "__cause__", None)
    constraint_name = getattr(getattr(cause, "diag", None), "constraint_name", None)
    if constraint_name == "doctor_phone_global_unique":
        return True
    message = " ".join(str(value) for value in exc.args).lower()
    return (
        "doctor_phone_global_unique" in message
        or "doctors_doctorprofile.phone" in message
    )


def _raise_phone_validation_error(exc: IntegrityError):
    if _is_phone_unique_error(exc):
        raise ValidationError({"phone": ["手机号已存在"]}) from exc
    raise exc


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
            changes={"changed_fields": ["department", "employee_no", "gender", "name", "phone", "title"]},
            request_id=request_id,
        )
        return doctor


def create_doctor(*, name, gender, phone, department, title, actor=None, request_id=""):
    phone = normalize_phone(phone)
    try:
        return run_with_database_retry(
            lambda: _create_doctor_once(
                    name=name, gender=gender, phone=phone, department=department, title=title,
                    actor=actor, request_id=request_id,
            )
        )
    except IntegrityError as exc:
        _raise_phone_validation_error(exc)


def update_doctor(*, actor, doctor, request_id: str, **changes):
    editable = {key: value for key, value in changes.items() if key in {"name", "gender", "phone", "department", "title"}}
    if "phone" in editable:
        editable["phone"] = normalize_phone(editable["phone"])
    if not editable:
        return doctor
    try:
        with transaction.atomic():
            locked = DoctorProfile.objects.select_for_update().get(pk=doctor.pk, deleted_at__isnull=True)
            for key, value in editable.items():
                setattr(locked, key, value)
            locked.save(update_fields=[*editable.keys(), "updated_at"])
            record(
                actor=actor,
                action="doctor.update",
                target=locked,
                changes={"changed_fields": sorted(editable)},
                request_id=request_id,
            )
            return locked
    except IntegrityError as exc:
        _raise_phone_validation_error(exc)


def set_doctor_active(*, actor, doctor, is_active: bool, request_id: str):
    with transaction.atomic():
        locked = (
            DoctorProfile.objects.select_for_update(of=("self",))
            .select_related("user")
            .get(pk=doctor.pk)
        )
        if locked.deleted_at is not None:
            raise NotFound("医生不存在", code="not_found")
        if (
            actor.role == Role.DOCTOR
            and not is_active
            and locked.user_id == actor.pk
        ):
            raise PermissionDenied(
                "医生不能停用自己",
                code="doctor_status_self_forbidden",
            )
        if not is_active:
            _ensure_no_active_patients(locked, action="deactivate")
        locked_user = User.objects.select_for_update().get(pk=locked.user_id)
        if locked_user.deleted_at is not None:
            raise NotFound("医生不存在", code="not_found")
        if actor.role == Role.DOCTOR and locked_user.role == Role.SYSTEM_ADMIN:
            raise PermissionDenied(
                "医生不能变更系统管理员状态",
                code="doctor_status_target_forbidden",
            )
        if locked_user.role != Role.DOCTOR:
            raise PermissionDenied(
                "仅可变更医生账号状态",
                code="doctor_status_target_invalid",
            )
        if locked_user.is_active == is_active:
            locked.user = locked_user
            return locked
        updated_user = update_account_security_state(
            actor=actor,
            target=locked_user,
            is_active=is_active,
            request_id=request_id,
        )
        locked.user = updated_user
        record(
            actor=actor,
            action="doctor.activate" if is_active else "doctor.deactivate",
            target=locked,
            changes={"status": {"from": not is_active, "to": is_active}},
            request_id=request_id,
        )
        return locked


def soft_delete_doctor(*, actor, doctor, request_id: str):
    with transaction.atomic():
        locked = DoctorProfile.objects.select_for_update(of=("self",)).select_related("user").get(pk=doctor.pk, deleted_at__isnull=True)
        _ensure_no_active_patients(locked)
        update_account_security_state(actor=actor, target=locked.user, is_active=False, deleted=True, request_id=request_id)
        locked.deleted_at = timezone.now()
        locked.save(update_fields=["deleted_at"])
        record(actor=actor, action="doctor.delete", target=locked, changes={"deleted": True}, request_id=request_id)
        return locked
