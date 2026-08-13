from concurrent.futures import ThreadPoolExecutor

import pytest
from django.db import IntegrityError
from rest_framework.test import APIClient

from apps.accounts.models import RefreshToken, Role, User
from apps.accounts.services import change_password, reset_password, update_account_security_state
from apps.accounts.tokens import issue_token_pair
from apps.audit.models import AuditLog


@pytest.mark.django_db(transaction=True)
def test_concurrent_refresh_consumes_old_token_only_once_and_revokes_family_on_reuse():
    user = User.objects.create_user(
        login_id="doctor-concurrent", password="888888", role=Role.DOCTOR
    )
    login_response = APIClient().post(
        "/api/v1/auth/login/",
        {"login_id": user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    refresh = login_response.json()["data"]["refresh"]

    def perform_refresh():
        return APIClient().post(
            "/api/v1/auth/refresh/",
            {"client_kind": "android", "refresh": refresh},
            format="json",
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        responses = list(executor.map(lambda _: perform_refresh(), range(2)))

    assert sorted(response.status_code for response in responses) == [200, 401]
    assert RefreshToken.objects.filter(parent__isnull=False).count() == 1
    assert not RefreshToken.objects.filter(user=user, revoked_at__isnull=True).exists()


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("changes", "expected_active", "expected_deleted"),
    [({"is_active": False}, False, False), ({"deleted": True}, True, True)],
)
def test_account_security_state_change_revokes_refresh_and_records_audit(
    changes, expected_active, expected_deleted
):
    actor = User.objects.create_user(
        login_id="security-admin", password="888888", role=Role.SYSTEM_ADMIN
    )
    target = User.objects.create_user(
        login_id="security-target", password="888888", role=Role.DOCTOR
    )
    issue_token_pair(target)

    update_account_security_state(
        actor=actor, target=target, request_id="security-state-1", **changes
    )

    target.refresh_from_db()
    assert target.is_active is expected_active
    assert (target.deleted_at is not None) is expected_deleted
    assert not RefreshToken.objects.filter(user=target, revoked_at__isnull=True).exists()
    assert AuditLog.objects.filter(
        actor=actor,
        target_id=target.id,
        action="account.security_state_changed",
        request_id="security-state-1",
    ).exists()


@pytest.mark.django_db
def test_password_reset_rolls_back_password_and_refresh_revocation_when_audit_fails(monkeypatch):
    actor = User.objects.create_user(
        login_id="rollback-admin", password="888888", role=Role.SYSTEM_ADMIN
    )
    target = User.objects.create_user(
        login_id="rollback-target", password="888888", role=Role.DOCTOR
    )
    pair = issue_token_pair(target)

    def fail_record(**kwargs):
        raise IntegrityError("audit insert failed")

    monkeypatch.setattr("apps.accounts.services.record", fail_record)
    with pytest.raises(IntegrityError, match="audit insert failed"):
        reset_password(
            actor=actor,
            user=target,
            new_password="temporary-password",
            request_id="rollback-request",
        )

    target.refresh_from_db()
    assert target.check_password("888888")
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(pair.refresh)).revoked_at is None


@pytest.mark.django_db
def test_password_change_rolls_back_when_audit_fails(monkeypatch):
    target = User.objects.create_user(
        login_id="change-rollback-target", password="888888", role=Role.DOCTOR
    )
    pair = issue_token_pair(target)

    def fail_record(**kwargs):
        raise IntegrityError("audit insert failed")

    monkeypatch.setattr("apps.accounts.services.record", fail_record)
    with pytest.raises(IntegrityError, match="audit insert failed"):
        change_password(
            user=target,
            old_password="888888",
            new_password="new-strong-password",
            request_id="change-rollback-request",
        )

    target.refresh_from_db()
    assert target.check_password("888888")
    assert target.must_change_password
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(pair.refresh)).revoked_at is None


@pytest.mark.django_db
def test_account_security_state_rolls_back_when_audit_fails(monkeypatch):
    actor = User.objects.create_user(
        login_id="state-rollback-admin", password="888888", role=Role.SYSTEM_ADMIN
    )
    target = User.objects.create_user(
        login_id="state-rollback-target", password="888888", role=Role.DOCTOR
    )
    pair = issue_token_pair(target)

    def fail_record(**kwargs):
        raise IntegrityError("audit insert failed")

    monkeypatch.setattr("apps.accounts.services.record", fail_record)
    with pytest.raises(IntegrityError, match="audit insert failed"):
        update_account_security_state(
            actor=actor,
            target=target,
            is_active=False,
            request_id="state-rollback-request",
        )

    target.refresh_from_db()
    assert target.is_active
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(pair.refresh)).revoked_at is None


@pytest.mark.django_db
def test_refresh_rotation_rolls_back_when_audit_fails(monkeypatch):
    target = User.objects.create_user(
        login_id="refresh-rollback-target", password="888888", role=Role.DOCTOR
    )
    login_response = APIClient().post(
        "/api/v1/auth/login/",
        {"login_id": target.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    raw_refresh = login_response.json()["data"]["refresh"]

    def fail_record(**kwargs):
        raise IntegrityError("audit insert failed")

    monkeypatch.setattr("apps.accounts.views.record", fail_record)
    response = APIClient().post(
        "/api/v1/auth/refresh/",
        {"client_kind": "android", "refresh": raw_refresh},
        format="json",
    )

    assert response.status_code == 500
    original = RefreshToken.objects.get(token_hash=RefreshToken.digest(raw_refresh))
    assert original.revoked_at is None
    assert not RefreshToken.objects.filter(parent=original).exists()
