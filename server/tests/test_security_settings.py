import os
import subprocess
import sys

from django.conf import settings


def test_production_base_settings_reject_missing_secret_key():
    environment = os.environ.copy()
    environment.pop("DJANGO_SECRET_KEY", None)
    environment["DJANGO_SETTINGS_MODULE"] = "vocaease.settings.base"

    result = subprocess.run(
        [sys.executable, "-c", "from django.conf import settings; print(settings.SECRET_KEY)"],
        capture_output=True,
        text=True,
        env=environment,
    )

    assert result.returncode != 0
    assert "DJANGO_SECRET_KEY" in result.stderr


def test_test_settings_use_non_public_development_secret():
    assert len(settings.SECRET_KEY.encode()) >= 32
    assert settings.SECRET_KEY != "change-me"
