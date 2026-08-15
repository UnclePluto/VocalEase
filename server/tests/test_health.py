def test_live_health(client):
    response = client.get("/health/live/")
    assert response.status_code == 200
    assert response.json()["data"] == {"status": "ok"}


def test_ready_health_reports_dependencies(client, monkeypatch):
    monkeypatch.setattr(
        "vocaease.health.check_dependencies", lambda: {"database": True, "redis": True, "storage": True}
    )
    response = client.get("/health/ready/")
    assert response.status_code == 200
    assert response.json()["data"]["database"] is True
    assert response.json()["data"]["storage"] is True


def test_ready_health_returns_safe_503_envelope_when_dependency_fails(client, monkeypatch):
    monkeypatch.setattr(
        "vocaease.health.check_dependencies",
        lambda: (_ for _ in ()).throw(OSError("/secret/internal/path")),
    )

    response = client.get("/health/ready/")

    assert response.status_code == 503
    assert response.json()["code"] == "service_unavailable"
    assert "/secret/internal/path" not in response.content.decode()
