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


def test_production_base_settings_fail_closed_when_qiniu_private_storage_is_incomplete():
    environment = os.environ.copy()
    environment["DJANGO_SETTINGS_MODULE"] = "vocaease.settings.base"
    environment["DJANGO_SECRET_KEY"] = "a" * 32
    for key in ("QINIU_ACCESS_KEY", "QINIU_SECRET_KEY", "QINIU_BUCKET", "QINIU_DOMAIN", "QINIU_CALLBACK_URL"):
        environment.pop(key, None)

    result = subprocess.run(
        [sys.executable, "-c", "from django.conf import settings; print(settings.MEDIA_BACKEND)"],
        capture_output=True,
        text=True,
        env=environment,
    )

    assert result.returncode != 0
    assert "七牛私有空间" in result.stderr


def test_test_settings_use_non_public_development_secret():
    assert len(settings.SECRET_KEY.encode()) >= 32
    assert settings.SECRET_KEY != "change-me"
