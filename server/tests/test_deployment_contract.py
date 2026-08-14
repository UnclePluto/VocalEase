import json
import subprocess
import tomllib
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def compose_config():
    result = subprocess.run(
        ["docker", "compose", "-f", "deploy/compose.yaml", "config", "--format", "json"],
        cwd=REPOSITORY_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def test_compose_services_reference_the_existing_server_dockerfile():
    services = compose_config()["services"]

    for service_name in ("server", "celery", "celery-beat"):
        build = services[service_name]["build"]
        dockerfile = Path(build["context"]) / build["dockerfile"]
        assert dockerfile == REPOSITORY_ROOT / "deploy/docker/server.Dockerfile"
        assert dockerfile.is_file()


def test_runtime_and_infrastructure_versions_match_the_technical_baseline():
    with (REPOSITORY_ROOT / "server/pyproject.toml").open("rb") as project_file:
        project = tomllib.load(project_file)["project"]

    assert project["requires-python"] == ">=3.13"
    assert "djangorestframework>=3.17,<4" in project["dependencies"]

    services = compose_config()["services"]
    assert services["postgres"]["image"] == "postgres:17-alpine"
    assert services["redis"]["image"] == "redis:8-alpine"


def test_compose_has_dedicated_celery_beat_with_health_dependencies():
    services = compose_config()["services"]
    beat = services["celery-beat"]
    assert "celery" in beat["command"]
    assert "beat" in beat["command"]
    assert beat["depends_on"]["postgres"]["condition"] == "service_healthy"
    assert beat["depends_on"]["redis"]["condition"] == "service_healthy"
    assert beat["build"] == services["celery"]["build"]
    assert beat["environment"] == services["celery"]["environment"]


def test_base_settings_define_serializable_recovery_beat_schedule(settings):
    schedule = settings.CELERY_BEAT_SCHEDULE
    assert schedule["recover-analysis-tasks"]["task"] == "apps.analysis.tasks.recover_analysis_tasks_task"
    assert schedule["refresh-song-availability"]["task"] == "apps.songs.tasks.start_song_availability_scan_task"
