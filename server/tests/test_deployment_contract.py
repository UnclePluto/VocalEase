import json
import os
import subprocess
import sys
import tomllib
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def compose_config():
    environment = os.environ.copy()
    environment.pop("DJANGO_SETTINGS_MODULE", None)
    result = subprocess.run(
        ["docker", "compose", "-f", "deploy/compose.yaml", "config", "--format", "json"],
        cwd=REPOSITORY_ROOT,
        env=environment,
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


def test_runtime_services_receive_all_media_and_logging_environment_contracts():
    services = compose_config()["services"]
    expected = {
        "LOG_LEVEL": "INFO",
        "MEDIA_SCANNER_MAX_MARKERS": "1000",
        "MEDIA_SCANNER_MAX_MARKER_BYTES": "65536",
        "QINIU_UPLOAD_URL": "https://up.qiniup.com",
        "QINIU_RS_HOST": "https://rs.qiniu.com",
        "QINIU_STAT_TIMEOUT_SECONDS": "5",
    }
    for service_name in ("server", "celery", "celery-beat"):
        for key, value in expected.items():
            assert services[service_name]["environment"][key] == value


def test_compose_explicitly_propagates_one_settings_module_to_all_runtime_services():
    services = compose_config()["services"]
    for service_name in ("server", "celery", "celery-beat"):
        assert services[service_name]["environment"]["DJANGO_SETTINGS_MODULE"] == "vocaease.settings.local"


def test_https_proxy_topology_preserves_edge_proto_without_public_backend_port():
    services = compose_config()["services"]
    assert services["web"]["ports"][0]["host_ip"] == "127.0.0.1"
    assert services["server"]["ports"][0]["host_ip"] == "127.0.0.1"
    nginx = (REPOSITORY_ROOT / "deploy/nginx/default.conf").read_text()
    assert "map $http_x_forwarded_proto $vocaease_forwarded_proto" in nginx
    assert "proxy_set_header X-Forwarded-Proto $vocaease_forwarded_proto;" in nginx
    assert "proxy_set_header X-Forwarded-Proto $scheme;" not in nginx
    readme = (REPOSITORY_ROOT / "README.md").read_text()
    assert "DJANGO_ALLOWED_HOSTS" in readme
    assert "AUTH_WEB_ALLOWED_ORIGINS" in readme
    assert "覆盖客户端传入的 X-Forwarded-Proto" in readme


def test_local_media_upload_proxy_limit_covers_configured_maximum(settings):
    upload_location = "location ^~ /api/v1/media/local-upload/ {"
    for config_path in ("deploy/nginx/default.conf", "deploy/openresty.vocaease.conf"):
        config = (REPOSITORY_ROOT / config_path).read_text()
        location_body = config.split(upload_location, 1)[1].split("}", 1)[0]
        limit = next(line.strip().split()[1].removesuffix("m;") for line in location_body.splitlines() if "client_max_body_size" in line)
        assert int(limit) * 1024 * 1024 >= settings.MEDIA_VIDEO_MAX_BYTES


def test_compose_production_path_rejects_placeholder_secret_key():
    environment = os.environ.copy()
    environment.update(
        {
            "DJANGO_SETTINGS_MODULE": "vocaease.settings.base",
            "DJANGO_SECRET_KEY": "change-me",
            "DJANGO_DEBUG": "false",
            "MEDIA_BACKEND": "qiniu",
            "QINIU_ACCESS_KEY": "test-access",
            "QINIU_SECRET_KEY": "test-secret",
            "QINIU_BUCKET": "test-bucket",
            "QINIU_DOMAIN": "https://media.example.test",
            "QINIU_CALLBACK_URL": "https://api.example.test/api/v1/media/qiniu/callback/",
        }
    )
    rendered = subprocess.run(
        ["docker", "compose", "-f", "deploy/compose.yaml", "config", "--format", "json"],
        cwd=REPOSITORY_ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )
    server_environment = json.loads(rendered.stdout)["services"]["server"]["environment"]
    assert server_environment["DJANGO_SETTINGS_MODULE"] == "vocaease.settings.base"
    process_environment = os.environ.copy()
    process_environment.update({key: str(value) for key, value in server_environment.items()})
    result = subprocess.run(
        [sys.executable, "-c", "from django.conf import settings; print(settings.SECRET_KEY)"],
        cwd=REPOSITORY_ROOT / "server",
        env=process_environment,
        capture_output=True,
        text=True,
    )
    assert result.returncode != 0
    assert "DJANGO_SECRET_KEY" in result.stderr


def test_all_compose_services_have_healthchecks_and_server_checks_readiness():
    services = compose_config()["services"]
    assert set(services) == {
        "postgres",
        "redis",
        "server",
        "celery",
        "celery-beat",
        "web",
    }
    for service_name in services:
        assert "healthcheck" in services[service_name], service_name
    server_probe = " ".join(services["server"]["healthcheck"]["test"])
    assert "/health/ready/" in server_probe
    assert "/health/live/" not in server_probe
    assert services["web"]["depends_on"]["server"]["condition"] == "service_healthy"


def test_compose_host_ports_can_be_isolated_for_parallel_qa_runs():
    environment = os.environ.copy()
    environment.update({"SERVER_PORT": "18013", "WEB_PORT": "13013"})
    result = subprocess.run(
        ["docker", "compose", "-f", "deploy/compose.yaml", "config", "--format", "json"],
        cwd=REPOSITORY_ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )
    services = json.loads(result.stdout)["services"]
    assert services["server"]["ports"][0]["published"] == "18013"
    assert services["web"]["ports"][0]["published"] == "13013"


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


def test_playwright_browser_path_is_portable_and_documented():
    config = (REPOSITORY_ROOT / "web-admin/playwright.config.ts").read_text()
    readme = (REPOSITORY_ROOT / "README.md").read_text()
    assert "/Users/" not in config
    assert "PLAYWRIGHT_CHROMIUM_EXECUTABLE" in config
    assert "playwright install chromium" in readme


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
