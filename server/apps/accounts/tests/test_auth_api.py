import pytest
from django.test import Client
from django.test import override_settings
from rest_framework.test import APIClient

from apps.accounts.models import RefreshToken, Role, User
from apps.accounts.services import reset_password
from apps.audit.models import AuditLog


@pytest.fixture
def api_client():
    return APIClient()


@pytest.fixture
def doctor_user(db):
    return User.objects.create_user(
        login_id="doctor-001", password="888888", role=Role.DOCTOR
    )


@pytest.fixture
def patient_user(db):
    return User.objects.create_user(
        login_id="patient-001", password="888888", role=Role.PATIENT
    )


@pytest.fixture
def system_admin_user(db):
    return User.objects.create_user(
        login_id="admin-001", password="888888", role=Role.SYSTEM_ADMIN
    )


@pytest.mark.django_db
def test_initial_password_requires_change(api_client, doctor_user):
    response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "web"},
        format="json",
    )
    assert response.status_code == 200
    access = response.json()["data"]["access"]
    api_client.credentials(HTTP_AUTHORIZATION=f"Bearer {access}")
    blocked = api_client.get("/api/v1/admin/me/")
    assert blocked.status_code == 403
    assert blocked.json()["code"] == "password_change_required"


@pytest.mark.django_db
def test_patient_cannot_use_admin_namespace(api_client, patient_user):
    api_client.force_authenticate(patient_user)
    assert api_client.get("/api/v1/admin/me/").status_code == 403


@pytest.mark.django_db
def test_django_admin_rejects_non_system_admin(doctor_user):
    client = Client()
    client.force_login(doctor_user)
    assert client.get("/internal/admin/").status_code == 403


@pytest.mark.django_db
def test_superuser_creation_cannot_assign_non_system_admin_role():
    with pytest.raises(ValueError, match="系统管理员"):
        User.objects.create_superuser(
            login_id="bad-superuser", password="888888", role=Role.DOCTOR
        )


@pytest.mark.django_db
def test_web_login_sets_http_only_refresh_cookie_and_records_request_audit(api_client, doctor_user):
    response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "web"},
        format="json",
        HTTP_X_REQUEST_ID="login-request-1",
    )

    assert response.status_code == 200
    assert response.json()["request_id"] == "login-request-1"
    assert "refresh" not in response.json()["data"]
    cookie = response.cookies["refresh_token"]
    assert cookie["httponly"]
    assert cookie["samesite"] == "Lax"
    assert cookie["secure"]
    audit = AuditLog.objects.get(action="auth.login")
    assert audit.actor == doctor_user
    assert audit.request_id == "login-request-1"


@pytest.mark.django_db
def test_android_login_returns_refresh_only_in_body_and_persists_its_hash(api_client, doctor_user):
    response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )

    assert response.status_code == 200
    refresh = response.json()["data"]["refresh"]
    assert "refresh_token" not in response.cookies
    stored = RefreshToken.objects.get(user=doctor_user)
    assert stored.token_hash == RefreshToken.digest(refresh)
    assert stored.token_hash != refresh


@pytest.mark.django_db
def test_android_refresh_rotates_and_revokes_previous_refresh_token(api_client, doctor_user):
    login_response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    old_refresh = login_response.json()["data"]["refresh"]

    response = api_client.post(
        "/api/v1/auth/refresh/", {"refresh": old_refresh}, format="json"
    )

    assert response.status_code == 200
    new_refresh = response.json()["data"]["refresh"]
    assert new_refresh != old_refresh
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(old_refresh)).revoked_at is not None
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(new_refresh)).revoked_at is None
    assert AuditLog.objects.filter(action="auth.refresh", actor=doctor_user).exists()


@pytest.mark.django_db
def test_logout_revokes_refresh_token_and_records_audit(api_client, doctor_user):
    login_response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    data = login_response.json()["data"]
    api_client.credentials(HTTP_AUTHORIZATION=f"Bearer {data['access']}")

    response = api_client.post(
        "/api/v1/auth/logout/", {"refresh": data["refresh"]}, format="json"
    )

    assert response.status_code == 200
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(data["refresh"])).revoked_at is not None
    assert AuditLog.objects.filter(action="auth.logout", actor=doctor_user).exists()


@pytest.mark.django_db
def test_change_password_removes_first_login_gate_revokes_refreshes_and_redacts_audit(
    api_client, system_admin_user
):
    login_response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": system_admin_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    data = login_response.json()["data"]
    api_client.credentials(HTTP_AUTHORIZATION=f"Bearer {data['access']}")
    assert api_client.get("/api/v1/admin/me/").status_code == 403

    response = api_client.post(
        "/api/v1/auth/change-password/",
        {"old_password": "888888", "new_password": "new-strong-password"},
        format="json",
    )

    assert response.status_code == 200
    system_admin_user.refresh_from_db()
    assert not system_admin_user.must_change_password
    assert system_admin_user.check_password("new-strong-password")
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(data["refresh"])).revoked_at is not None
    assert api_client.get("/api/v1/admin/me/").status_code == 200
    audit = AuditLog.objects.get(action="auth.change_password")
    assert audit.changes == {"old_password": "[REDACTED]", "new_password": "[REDACTED]"}


@pytest.mark.django_db
@override_settings(AUTH_REFRESH_COOKIE_SECURE=False)
def test_http_local_environment_keeps_refresh_cookie_secure_flag_disabled(api_client, doctor_user):
    response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "web"},
        format="json",
    )

    assert response.status_code == 200
    assert not response.cookies["refresh_token"]["secure"]


@pytest.mark.django_db
def test_reset_password_requires_next_login_change_and_records_redacted_audit(
    doctor_user, system_admin_user
):
    reset_password(
        actor=system_admin_user,
        user=doctor_user,
        new_password="temporary-password",
        request_id="password-reset-1",
    )

    doctor_user.refresh_from_db()
    assert doctor_user.must_change_password
    assert doctor_user.check_password("temporary-password")
    audit = AuditLog.objects.get(action="auth.reset_password")
    assert audit.actor == system_admin_user
    assert audit.target_id == doctor_user.id
    assert audit.request_id == "password-reset-1"
    assert audit.changes == {"new_password": "[REDACTED]"}
