from fastapi.testclient import TestClient
from vocaease_api.app import create_app


def test_health_reports_real_dependencies(monkeypatch, tmp_path):
    media_directory = tmp_path / "media"
    monkeypatch.setenv(
        "VOCAEASE_DATABASE_URL",
        "postgresql+psycopg://vocaease:vocaease_dev@127.0.0.1:54329/vocaease",
    )
    monkeypatch.setenv("VOCAEASE_REDIS_URL", "redis://127.0.0.1:63799/0")
    monkeypatch.setenv("VOCAEASE_MEDIA_DIRECTORY", str(media_directory))

    with TestClient(create_app()) as client:
        response = client.get("/api/v1/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "healthy",
        "dependencies": {
            "database": "up",
            "redis": "up",
            "media_storage": "up",
        },
    }
    assert media_directory.is_dir()
