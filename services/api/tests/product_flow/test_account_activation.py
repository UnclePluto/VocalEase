from uuid import uuid4

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from vocaease_api.app import create_app

DATABASE_URL = "postgresql+psycopg://vocaease:vocaease_dev@127.0.0.1:54329/vocaease"


def test_admin_creates_participant_and_participant_must_change_password(monkeypatch):
    suffix = uuid4().hex[:10]
    phone = f"139{int(suffix, 16) % 100_000_000:08d}"
    research_code = f"TEST-{suffix}"
    monkeypatch.setenv("VOCAEASE_DATABASE_URL", DATABASE_URL)
    monkeypatch.setenv("VOCAEASE_BOOTSTRAP_ADMIN_USERNAME", "admin")
    monkeypatch.setenv("VOCAEASE_BOOTSTRAP_ADMIN_PASSWORD", "admin888888")

    with TestClient(create_app()) as client:
        admin_login = client.post(
            "/api/v1/auth/admin/login",
            json={"username": "admin", "password": "admin888888"},
        )
        assert admin_login.status_code == 200
        admin_headers = {"Authorization": f"Bearer {admin_login.json()['access_token']}"}

        created = client.post(
            "/api/v1/admin/participants",
            headers=admin_headers,
            json={"name": "测试患者", "phone": phone, "research_code": research_code},
        )
        assert created.status_code == 201
        participant_id = created.json()["id"]
        assert created.json()["must_change_password"] is True

        participant_login = client.post(
            "/api/v1/auth/participant/login",
            json={"phone": phone, "password": "88888888"},
        )
        assert participant_login.status_code == 200
        assert participant_login.json()["must_change_password"] is True
        participant_headers = {
            "Authorization": f"Bearer {participant_login.json()['access_token']}"
        }

        blocked = client.get("/api/v1/participant/home", headers=participant_headers)
        assert blocked.status_code == 403
        assert blocked.json()["detail"] == "必须先修改初始密码"

        changed = client.post(
            "/api/v1/auth/participant/change-password",
            headers=participant_headers,
            json={"current_password": "88888888", "new_password": "Secure-2026-pass"},
        )
        assert changed.status_code == 200
        changed_headers = {"Authorization": f"Bearer {changed.json()['access_token']}"}
        assert client.get("/api/v1/participant/home", headers=changed_headers).status_code == 200

        old_password = client.post(
            "/api/v1/auth/participant/login",
            json={"phone": phone, "password": "88888888"},
        )
        assert old_password.status_code == 401

        active_login = client.post(
            "/api/v1/auth/participant/login",
            json={"phone": phone, "password": "Secure-2026-pass"},
        )
        assert active_login.status_code == 200
        active_headers = {"Authorization": f"Bearer {active_login.json()['access_token']}"}
        assert client.get("/api/v1/participant/home", headers=active_headers).status_code == 200

        disabled = client.patch(
            f"/api/v1/admin/participants/{participant_id}",
            headers=admin_headers,
            json={"active": False},
        )
        assert disabled.status_code == 200
        assert client.get("/api/v1/participant/home", headers=active_headers).status_code == 401

        restored = client.patch(
            f"/api/v1/admin/participants/{participant_id}",
            headers=admin_headers,
            json={"active": True},
        )
        assert restored.status_code == 200

        reset = client.post(
            f"/api/v1/admin/participants/{participant_id}/reset-password",
            headers=admin_headers,
        )
        assert reset.status_code == 204

        reset_login = client.post(
            "/api/v1/auth/participant/login",
            json={"phone": phone, "password": "88888888"},
        )
        assert reset_login.status_code == 200
        assert reset_login.json()["must_change_password"] is True

    engine = create_engine(DATABASE_URL)
    with engine.connect() as connection:
        password_hash = connection.execute(
            text("SELECT password_hash FROM accounts WHERE id = :id"),
            {"id": created.json()["account_id"]},
        ).scalar_one()
    engine.dispose()
    assert password_hash != "88888888"
    assert "88888888" not in password_hash
