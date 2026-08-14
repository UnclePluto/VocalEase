import json
import os
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


def test_runtime_services_share_one_private_local_media_volume():
    config = compose_config()
    expected_target = "/app/private-media"
    sources = set()
    for service_name in ("server", "celery", "celery-beat"):
        service = config["services"][service_name]
        assert service["environment"]["MEDIA_LOCAL_ROOT"] == expected_target
        assert service["environment"]["MEDIA_BACKEND"] == "local"
        media_mounts = [mount for mount in service["volumes"] if mount["target"] == expected_target]
        assert len(media_mounts) == 1
        assert media_mounts[0]["type"] == "volume"
        sources.add(media_mounts[0]["source"])
    assert len(sources) == 1
    assert not any(
        mount["target"] == expected_target
        for service_name in ("postgres", "redis")
        for mount in config["services"][service_name].get("volumes", [])
    )


def test_server_image_prepares_private_media_directory_before_runtime():
    dockerfile = (REPOSITORY_ROOT / "deploy/docker/server.Dockerfile").read_text()
    assert "/app/private-media" in dockerfile
    assert "USER app" in dockerfile
    assert "ENTRYPOINT" in dockerfile


def test_server_image_does_not_copy_host_virtualenv_or_test_caches():
    dockerignore = (REPOSITORY_ROOT / ".dockerignore").read_text().splitlines()
    assert "server/.venv" in dockerignore
    assert "**/__pycache__" in dockerignore
    assert "server/.pytest_cache" in dockerignore


def test_runtime_commands_never_sync_dependencies_on_startup():
    services = compose_config()["services"]
    for service_name in ("server", "celery", "celery-beat"):
        assert "--no-sync" in services[service_name]["command"]


def test_non_integer_analysis_lease_is_reported_as_stable_system_check_error():
    environment = os.environ.copy()
    environment.update({
        "DJANGO_SETTINGS_MODULE": "vocaease.settings.test",
        "ANALYSIS_TASK_LEASE_SECONDS": "not-an-integer",
    })
    result = subprocess.run(
        ["uv", "run", "python", "manage.py", "check"],
        cwd=REPOSITORY_ROOT / "server",
        env=environment,
        capture_output=True,
        text=True,
    )
    output = result.stdout + result.stderr
    assert result.returncode == 1
    assert "analysis.E001" in output
    assert "Traceback" not in output
