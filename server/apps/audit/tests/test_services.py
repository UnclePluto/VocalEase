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


@pytest.mark.django_db
def test_record_recursively_redacts_credentials_and_medical_notes():
    doctor_user = User.objects.create_user(
        login_id="doctor-audit-002", password="888888", role=Role.DOCTOR
    )
    audit = record(
        actor=doctor_user,
        action="patient.update",
        target=doctor_user,
        changes={
            "profile": {
                "Password": "secret",
                "credentials": [
                    {"refresh_token": "refresh-secret"},
                    {"accessToken": "access-secret"},
                    {"AUTHORIZATION": "Bearer secret"},
                ],
            },
            "medical_notes": "患者完整医疗备注原文",
        },
        request_id="audit-request-2",
    )

    serialized = str(audit.changes)
    assert "secret" not in serialized
    assert "患者完整医疗备注原文" not in serialized
    assert audit.changes["profile"]["Password"] == "[REDACTED]"
    assert audit.changes["profile"]["credentials"] == [
        {"refresh_token": "[REDACTED]"},
        {"accessToken": "[REDACTED]"},
        {"AUTHORIZATION": "[REDACTED]"},
    ]
    assert audit.changes["medical_notes"].startswith("[MEDICAL_CONTENT_REDACTED sha256:")
