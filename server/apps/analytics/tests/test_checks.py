from pathlib import Path

from django.core.checks import run_checks


def _analytics_error_ids():
    return {message.id for message in run_checks() if message.id.startswith("analytics.")}


def test_analytics_export_runtime_boundaries_are_valid_by_default():
    assert _analytics_error_ids() == set()


def test_analytics_export_runtime_boundaries_reject_invalid_relationships(settings):
    settings.ANALYTICS_EXPORT_TTL_SECONDS = 10
    settings.ANALYTICS_EXPORT_LEASE_SECONDS = 20
    settings.ANALYTICS_EXPORT_HEARTBEAT_SECONDS = 0
    settings.ANALYTICS_EXPORT_MAX_ATTEMPTS = 0

    errors = _analytics_error_ids()

    assert {"analytics.E002", "analytics.E003", "analytics.E004"} <= errors


def test_analytics_export_runtime_boundaries_reject_invalid_threshold_and_nonintegers(settings):
    settings.ANALYTICS_SYNC_EXPORT_LIMIT = "many"
    settings.ANALYTICS_EXPORT_TTL_SECONDS = "forever"
    settings.ANALYTICS_EXPORT_LEASE_SECONDS = -1
    settings.ANALYTICS_EXPORT_HEARTBEAT_SECONDS = 1
    settings.ANALYTICS_EXPORT_MAX_ATTEMPTS = "four"

    errors = _analytics_error_ids()

    assert {"analytics.E001", "analytics.E002", "analytics.E004"} <= errors


def test_invalid_integer_environment_is_reported_without_settings_import_traceback():
    import os
    import subprocess
    import sys

    environment = {
        **os.environ,
        "ANALYTICS_SYNC_EXPORT_LIMIT": "many",
        "ANALYTICS_EXPORT_TTL_SECONDS": "forever",
        "ANALYTICS_EXPORT_LEASE_SECONDS": "soon",
        "ANALYTICS_EXPORT_HEARTBEAT_SECONDS": "often",
        "ANALYTICS_EXPORT_MAX_ATTEMPTS": "several",
        "MEDIA_PRIVATE_URL_TTL_SECONDS": "briefly",
    }
    result = subprocess.run(
        [sys.executable, "manage.py", "check", "--settings=vocaease.settings.test"],
        cwd=Path(__file__).resolve().parents[3],
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 1
    assert "analytics.E001" in result.stderr and "analytics.E002" in result.stderr
    assert "Traceback" not in result.stderr
