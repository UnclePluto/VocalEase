def test_live_health(client):
    response = client.get("/health/live/")
    assert response.status_code == 200
    assert response.json()["data"] == {"status": "ok"}


def test_ready_health_reports_dependencies(client, monkeypatch):
    monkeypatch.setattr(
        "vocaease.health.check_dependencies", lambda: {"database": True, "redis": True}
    )
    response = client.get("/health/ready/")
    assert response.status_code == 200
    assert response.json()["data"]["database"] is True
