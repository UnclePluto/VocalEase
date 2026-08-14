import pytest
from django.test import Client
from django.test import override_settings
from rest_framework.test import APIClient
from rest_framework.throttling import ScopedRateThrottle
from rest_framework_simplejwt.tokens import AccessToken, RefreshToken as JWTRefreshToken

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
@pytest.mark.parametrize("state", ["password_change", "inactive", "deleted"])
def test_django_admin_rejects_system_admin_with_unsafe_account_state(system_admin_user, state):
    from django.utils import timezone

    system_admin_user.must_change_password = state == "password_change"
    system_admin_user.is_active = state != "inactive"
    system_admin_user.deleted_at = timezone.now() if state == "deleted" else None
    system_admin_user.save()
    client = Client()
    client.force_login(system_admin_user)

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
    refresh_cookie = response.cookies["refresh_token"]
    assert refresh_cookie["path"] == "/api/v1/auth/"
    assert refresh_cookie["httponly"]
    assert refresh_cookie["samesite"] == "Lax"
    assert refresh_cookie["secure"]
    csrf_cookie = response.cookies["refresh_csrf_token"]
    assert csrf_cookie["path"] == "/"
    assert not csrf_cookie["httponly"]
    assert csrf_cookie["samesite"] == "Lax"
    assert csrf_cookie["secure"]
    audit = AuditLog.objects.get(action="auth.login")
    assert audit.actor == doctor_user
    assert audit.request_id == "login-request-1"


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("role", "login_id"),
    [
        (Role.SYSTEM_ADMIN, "snapshot-admin"),
        (Role.DOCTOR, "snapshot-doctor"),
        (Role.PATIENT, "snapshot-patient"),
    ],
)
def test_login_returns_a_minimal_fresh_account_snapshot(api_client, role, login_id):
    user = User.objects.create_user(login_id=login_id, password="888888", role=role)

    response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": login_id, "password": "888888", "client_kind": "web"},
        format="json",
    )

    assert response.status_code == 200
    assert response.json()["data"]["user"] == {
        "login_id": login_id,
        "role": role,
        "must_change_password": True,
    }


@pytest.mark.django_db
def test_refresh_returns_current_account_snapshot(api_client, doctor_user):
    login_response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    refresh = login_response.json()["data"]["refresh"]
    doctor_user.must_change_password = False
    doctor_user.save(update_fields=["must_change_password"])

    response = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "android", "refresh": refresh},
        format="json",
    )

    assert response.status_code == 200
    assert response.json()["data"]["user"] == {
        "login_id": doctor_user.login_id,
        "role": Role.DOCTOR,
        "must_change_password": False,
    }


@pytest.mark.django_db
def test_oversized_request_id_is_bounded_before_api_and_audit_use(api_client, doctor_user):
    response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
        HTTP_X_REQUEST_ID="request-" + "x" * 500,
    )

    assert response.status_code == 200
    assert len(response.json()["request_id"]) <= 64
    assert response["X-Request-ID"] == response.json()["request_id"]
    assert AuditLog.objects.get(action="auth.login").request_id == response.json()["request_id"]


@pytest.mark.django_db
def test_web_refresh_requires_allowed_origin_and_double_submit_csrf(api_client, doctor_user):
    login_response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "web"},
        format="json",
    )
    csrf_token = login_response.cookies["refresh_csrf_token"].value

    missing_proof = api_client.post(
        "/api/v1/auth/refresh/", {"client_kind": "web"}, format="json"
    )
    assert missing_proof.status_code == 403

    wrong_origin = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "web"},
        format="json",
        HTTP_ORIGIN="https://evil.example",
        HTTP_X_CSRFTOKEN=csrf_token,
    )
    assert wrong_origin.status_code == 403

    wrong_csrf = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "web"},
        format="json",
        HTTP_ORIGIN="https://app.vocaease.test",
        HTTP_X_CSRFTOKEN="incorrect-token",
    )
    assert wrong_csrf.status_code == 403


@pytest.mark.django_db
def test_refresh_carrier_is_selected_only_by_explicit_client_kind(api_client, doctor_user):
    web_login = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "web"},
        format="json",
    )
    web_refresh = web_login.cookies["refresh_token"].value
    csrf_token = web_login.cookies["refresh_csrf_token"].value

    web_with_body = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "web", "refresh": web_refresh},
        format="json",
        HTTP_ORIGIN="https://app.vocaease.test",
        HTTP_X_CSRFTOKEN=csrf_token,
    )
    assert web_with_body.status_code == 400

    android_login = APIClient().post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    android_refresh = android_login.json()["data"]["refresh"]
    android_with_cookie = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "android", "refresh": android_refresh},
        format="json",
    )
    assert android_with_cookie.status_code == 400

    web_success = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "web"},
        format="json",
        HTTP_ORIGIN="https://app.vocaease.test",
        HTTP_X_CSRFTOKEN=csrf_token,
    )
    assert web_success.status_code == 200
    assert "refresh" not in web_success.json()["data"]


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
def test_default_token_lifetimes_are_access_15_minutes_and_refresh_1_day(api_client, doctor_user):
    response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )

    access = AccessToken(response.json()["data"]["access"])
    refresh = JWTRefreshToken(response.json()["data"]["refresh"])
    assert access["exp"] - access["iat"] == 15 * 60
    assert refresh["exp"] - refresh["iat"] == 24 * 60 * 60


@pytest.mark.django_db
def test_remember_me_uses_30_day_refresh_lifetime(api_client, doctor_user):
    response = api_client.post(
        "/api/v1/auth/login/",
        {
            "login_id": doctor_user.login_id,
            "password": "888888",
            "client_kind": "android",
            "remember_me": True,
        },
        format="json",
    )

    assert response.status_code == 200
    refresh = JWTRefreshToken(response.json()["data"]["refresh"])
    assert refresh["exp"] - refresh["iat"] == 30 * 24 * 60 * 60


@pytest.mark.django_db
def test_android_refresh_rotates_and_revokes_previous_refresh_token(api_client, doctor_user):
    login_response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    old_refresh = login_response.json()["data"]["refresh"]

    response = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "android", "refresh": old_refresh},
        format="json",
    )

    assert response.status_code == 200
    new_refresh = response.json()["data"]["refresh"]
    assert new_refresh != old_refresh
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(old_refresh)).revoked_at is not None
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(new_refresh)).revoked_at is None
    assert AuditLog.objects.filter(action="auth.refresh", actor=doctor_user).exists()


@pytest.mark.django_db
def test_replaying_consumed_refresh_revokes_its_issued_successor(api_client, doctor_user):
    login_response = api_client.post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    first_refresh = login_response.json()["data"]["refresh"]
    rotated = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "android", "refresh": first_refresh},
        format="json",
    )
    second_refresh = rotated.json()["data"]["refresh"]

    replay = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "android", "refresh": first_refresh},
        format="json",
    )
    successor_after_replay = api_client.post(
        "/api/v1/auth/refresh/",
        {"client_kind": "android", "refresh": second_refresh},
        format="json",
    )

    assert replay.status_code == 401
    assert replay.json()["code"] == "token_reuse_detected"
    assert successor_after_replay.status_code == 401
    assert not RefreshToken.objects.filter(user=doctor_user, revoked_at__isnull=True).exists()


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
        "/api/v1/auth/logout/",
        {"client_kind": "android", "refresh": data["refresh"]},
        format="json",
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
    assert not response.cookies["refresh_csrf_token"]["secure"]


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


@pytest.mark.django_db
def test_system_admin_can_reset_user_password_through_throttled_http_api(
    api_client, doctor_user, system_admin_user
):
    system_admin_user.must_change_password = False
    system_admin_user.save(update_fields=["must_change_password"])
    pair_response = APIClient().post(
        "/api/v1/auth/login/",
        {"login_id": doctor_user.login_id, "password": "888888", "client_kind": "android"},
        format="json",
    )
    refresh = pair_response.json()["data"]["refresh"]
    doctor_user.set_password("doctor-custom-password")
    doctor_user.must_change_password = False
    doctor_user.save(update_fields=["password", "must_change_password"])
    api_client.force_authenticate(system_admin_user)

    response = api_client.post(
        f"/api/v1/admin/users/{doctor_user.id}/reset-password/",
        format="json",
        HTTP_X_REQUEST_ID="admin-reset-1",
    )

    assert response.status_code == 200
    doctor_user.refresh_from_db()
    assert doctor_user.check_password("888888")
    assert doctor_user.must_change_password
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(refresh)).revoked_at is not None
    assert AuditLog.objects.filter(
        action="auth.reset_password",
        actor=system_admin_user,
        target_id=doctor_user.id,
        request_id="admin-reset-1",
    ).exists()
    assert response.renderer_context["view"].throttle_scope == "auth_reset_password"


@pytest.mark.django_db
@pytest.mark.parametrize("role", [Role.DOCTOR, Role.PATIENT])
def test_non_system_admin_cannot_call_password_reset_api(api_client, doctor_user, role):
    actor = User.objects.create_user(
        login_id=f"reset-actor-{role}", password="888888", role=role,
        must_change_password=False,
    )
    api_client.force_authenticate(actor)

    response = api_client.post(
        f"/api/v1/admin/users/{doctor_user.id}/reset-password/", format="json"
    )

    assert response.status_code == 403


@pytest.mark.django_db
def test_password_reset_api_uses_its_independent_throttle_rate(
    api_client, doctor_user, system_admin_user, monkeypatch
):
    system_admin_user.must_change_password = False
    system_admin_user.save(update_fields=["must_change_password"])
    api_client.force_authenticate(system_admin_user)
    monkeypatch.setitem(
        ScopedRateThrottle.THROTTLE_RATES, "auth_reset_password", "1/min"
    )

    first = api_client.post(
        f"/api/v1/admin/users/{doctor_user.id}/reset-password/", format="json"
    )
    second = api_client.post(
        f"/api/v1/admin/users/{doctor_user.id}/reset-password/", format="json"
    )

    assert first.status_code == 200
    assert second.status_code == 429
