import pytest

from apps.audit.services import record
from apps.accounts.models import Role, User


@pytest.mark.django_db
def test_record_persists_target_request_id_and_redacts_sensitive_changes():
    doctor_user = User.objects.create_user(
        login_id="doctor-audit-001", password="888888", role=Role.DOCTOR
    )
    audit = record(
        actor=doctor_user,
        action="credential.upload",
        target=doctor_user,
        changes={"certificate_id": "cert-001", "token": "secret"},
        request_id="audit-request-1",
    )

    assert audit.target_type == "accounts.User"
    assert audit.target_id == doctor_user.id
    assert audit.request_id == "audit-request-1"
    assert audit.changes == {"certificate_id": "cert-001", "token": "[REDACTED]"}
