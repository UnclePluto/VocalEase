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


@pytest.mark.django_db
def test_record_redacts_phone_and_notes_variants_and_phone_text_without_corrupting_ids():
    doctor_user = User.objects.create_user(
        login_id="doctor-audit-003", password="888888", role=Role.DOCTOR
    )
    audit = record(
        actor=doctor_user,
        action="patient.update",
        target=doctor_user,
        changes={
            "contacts": [
                {"phone_no": "13800000001", "phoneNo": "13900000002"},
                ({"phoneNumber": "+86 137-0000-0003"}, {"mobile_no": "136 0000 0004"}),
                {"mobileNo": "13500000005", "telephone": "13400000006", "tel": "13300000007"},
            ],
            "conditionNotes": {"nested": "完整病情"},
            "clinical_notes": ["完整临床备注"],
            "illness-notes": "完整疾病备注",
            "unknown_text": "回拨 +86 132-0000-0008 或 131 0000 0009",
            "target_uuid": "10000000-0000-0000-0000-000000000001",
            "employee_no": "D0001",
            "request_id": "audit-request-3",
            "ordinary_number": "12345678901",
        },
        request_id="audit-request-3",
    )

    serialized = str(audit.changes)
    for private_value in (
        "13800000001", "13900000002", "137-0000-0003", "136 0000 0004",
        "13500000005", "13400000006", "13300000007", "132-0000-0008",
        "131 0000 0009", "完整病情", "完整临床备注", "完整疾病备注",
    ):
        assert private_value not in serialized
    assert audit.changes["contacts"][0] == {
        "phone_no": "[PHONE_REDACTED]",
        "phoneNo": "[PHONE_REDACTED]",
    }
    assert audit.changes["conditionNotes"].startswith("[MEDICAL_CONTENT_REDACTED sha256:")
    assert audit.changes["clinical_notes"].startswith("[MEDICAL_CONTENT_REDACTED sha256:")
    assert audit.changes["illness-notes"].startswith("[MEDICAL_CONTENT_REDACTED sha256:")
    assert audit.changes["unknown_text"] == "回拨 [PHONE_REDACTED] 或 [PHONE_REDACTED]"
    assert audit.changes["target_uuid"] == "10000000-0000-0000-0000-000000000001"
    assert audit.changes["employee_no"] == "D0001"
    assert audit.changes["request_id"] == "audit-request-3"
    assert audit.changes["ordinary_number"] == "12345678901"


@pytest.mark.django_db
@pytest.mark.parametrize(
    "field_name",
    [
        "patient_notes",
        "notes_v2",
        "before-notes-after",
        "customMedicalNoteValue",
        "pre_clinical_notes_archive",
        "condition-note-v3",
        "legacyIllnessNotesCopy",
    ],
)
def test_record_redacts_note_markers_with_arbitrary_prefixes_and_suffixes(field_name):
    actor = User.objects.create_user(
        login_id=f"note-{field_name}"[:32], password="888888", role=Role.DOCTOR
    )

    audit = record(
        actor=actor,
        action="patient.update",
        target=actor,
        changes={field_name: {"content": "完整病情内容", "phone": "13800000003"}},
        request_id="audit-note-variants",
    )

    assert audit.changes[field_name].startswith("[MEDICAL_CONTENT_REDACTED sha256:")
    assert "完整病情内容" not in str(audit.changes)


@pytest.mark.django_db
@pytest.mark.parametrize(
    "private_text",
    [
        "13800000003",
        "138_0000_0003",
        "86:138:0000:0003",
        "+86_138:0000_0003",
        "＋８６：１３８＿００００：０００３",
        "+86.138.0000.0003",
        "86/138/0000/0003",
        "+86\u200b138\u200b0000\u200b0003",
        "＋８６．１３８．００００．０００３",
        "中文紧邻13800000003仍需隐藏",
        "中文紧邻１３８／００００／０００３仍需隐藏",
    ],
)
def test_record_redacts_obfuscated_chinese_mobile_numbers_in_arbitrary_text(private_text):
    actor = User.objects.create_user(
        login_id="phone-obfuscation", password="888888", role=Role.DOCTOR
    )

    audit = record(
        actor=actor,
        action="patient.update",
        target=actor,
        changes={"unknown": private_text},
        request_id="audit-phone-obfuscation",
    )

    assert audit.changes["unknown"].count("[PHONE_REDACTED]") == 1
    assert private_text not in str(audit.changes)


@pytest.mark.django_db
@pytest.mark.parametrize(
    "ordinary_text",
    [
        "D13800000003",
        "D138_0000_0003",
        "case13800000003x",
        "case138:0000:0003x",
        "10000000-0000-0000-0000-000000000001",
        "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
        "13:08:00",
        "case:12345678901",
        "2026-08-15",
        "D0001",
        "audit-request-4",
        "12345678901",
    ],
)
def test_record_does_not_redact_identifiers_dates_or_non_mobile_numbers(ordinary_text):
    actor = User.objects.create_user(
        login_id="phone-false-positive", password="888888", role=Role.DOCTOR
    )

    audit = record(
        actor=actor,
        action="patient.update",
        target=actor,
        changes={"unknown": ordinary_text},
        request_id="audit-phone-false-positive",
    )

    assert audit.changes["unknown"] == ordinary_text


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("supplied", "expected"),
    [
        ("", ""),
        ("cleanup:123e4567-e89b-12d3-a456-426614174000", "cleanup:123e4567-e89b-12d3-a456-426614174000"),
        ("13800000003", "invalid-request-id"),
        ("＋８６．１３８．００００．０００３", "invalid-request-id"),
        ("bad\nrequest", "invalid-request-id"),
        ("含有Unicode", "invalid-request-id"),
        ("x" * 65, "invalid-request-id"),
    ],
)
def test_record_defensively_sanitizes_direct_request_id(supplied, expected):
    actor = User.objects.create_user(
        login_id="request-id-defense", password="888888", role=Role.DOCTOR
    )

    audit = record(
        actor=actor,
        action="security.test",
        target=actor,
        changes={},
        request_id=supplied,
    )

    assert audit.request_id == expected
