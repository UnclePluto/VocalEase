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
    for attempt in range(20):
        try:
            with transaction.atomic():
                counter, _ = SequenceCounter.objects.select_for_update().get_or_create(
                    prefix=prefix, defaults={"value": 0}
                )
                counter.value += 1
                counter.save(update_fields=["value"])
                return f"{prefix}{counter.value:0{width}d}"
        except (IntegrityError, OperationalError):
            if attempt == 19:
                raise
            time.sleep(0.01 * (attempt + 1))
    raise RuntimeError("编号生成失败")


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
    for attempt in range(20):
        try:
            return _create_doctor_once(
                name=name, gender=gender, phone=phone, department=department, title=title,
                actor=actor, request_id=request_id,
            )
        except OperationalError:
            if attempt == 19:
                raise
            time.sleep(0.01 * (attempt + 1))
    raise RuntimeError("医生创建失败")


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

    with transaction.atomic():
        locked = DoctorProfile.objects.select_for_update().select_related("user").get(pk=doctor.pk, deleted_at__isnull=True)
        if TreatmentPlan.objects.filter(
            patient__primary_doctor=locked,
            patient__deleted_at__isnull=True,
            status=TreatmentPlan.Status.ACTIVE,
        ).exists():
            raise DoctorHasActivePatients()
        update_account_security_state(actor=actor, target=locked.user, is_active=False, deleted=True, request_id=request_id)
        locked.deleted_at = timezone.now()
        locked.save(update_fields=["deleted_at"])
        record(actor=actor, action="doctor.delete", target=locked, changes={"deleted": True}, request_id=request_id)
        return locked
