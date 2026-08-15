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


def test_production_base_settings_reject_placeholder_secret_key():
    environment = os.environ.copy()
    environment.update({
        "DJANGO_SETTINGS_MODULE": "vocaease.settings.base",
        "DJANGO_SECRET_KEY": "change-me",
        "DJANGO_DEBUG": "false",
        "MEDIA_BACKEND": "qiniu",
        "QINIU_ACCESS_KEY": "test-access",
        "QINIU_SECRET_KEY": "test-secret",
        "QINIU_BUCKET": "test-bucket",
        "QINIU_DOMAIN": "https://media.example.test",
        "QINIU_CALLBACK_URL": "https://api.example.test/api/v1/media/qiniu/callback/",
    })

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
    environment["MEDIA_ENVIRONMENT"] = "production"
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


def test_production_base_settings_reject_local_media_namespace():
    environment = os.environ.copy()
    environment.update({
        "DJANGO_SETTINGS_MODULE": "vocaease.settings.base",
        "DJANGO_SECRET_KEY": "a" * 32,
        "MEDIA_BACKEND": "qiniu",
        "MEDIA_ENVIRONMENT": "local",
        "QINIU_ACCESS_KEY": "test-access",
        "QINIU_SECRET_KEY": "test-secret",
        "QINIU_BUCKET": "test-bucket",
        "QINIU_DOMAIN": "https://media.example.test",
        "QINIU_CALLBACK_URL": "https://api.example.test/api/v1/media/qiniu/callback/",
    })

    result = subprocess.run(
        [sys.executable, "-c", "from django.conf import settings; print(settings.MEDIA_ENVIRONMENT)"],
        capture_output=True,
        text=True,
        env=environment,
    )

    assert result.returncode != 0
    assert "MEDIA_ENVIRONMENT" in result.stderr


def test_test_settings_use_non_public_development_secret():
    assert len(settings.SECRET_KEY.encode()) >= 32
    assert settings.SECRET_KEY != "change-me"


def test_production_base_settings_accept_explicit_https_hardening():
    environment = os.environ.copy()
    environment.update({
        "DJANGO_SETTINGS_MODULE": "vocaease.settings.base",
        "DJANGO_SECRET_KEY": "a" * 32,
        "DJANGO_DEBUG": "false",
        "DJANGO_SECURE_SSL_REDIRECT": "true",
        "DJANGO_SECURE_HSTS_SECONDS": "31536000",
        "DJANGO_SECURE_HSTS_INCLUDE_SUBDOMAINS": "true",
        "DJANGO_SECURE_HSTS_PRELOAD": "true",
        "MEDIA_BACKEND": "qiniu",
        "MEDIA_ENVIRONMENT": "production",
        "QINIU_ACCESS_KEY": "test-access",
        "QINIU_SECRET_KEY": "test-secret",
        "QINIU_BUCKET": "test-bucket",
        "QINIU_DOMAIN": "https://media.example.test",
        "QINIU_CALLBACK_URL": "https://api.example.test/api/v1/media/qiniu/callback/",
    })

    result = subprocess.run(
        [
            sys.executable,
            "-c",
            "from django.conf import settings; print(settings.SECURE_SSL_REDIRECT, settings.SECURE_HSTS_SECONDS, settings.SECURE_HSTS_INCLUDE_SUBDOMAINS, settings.SECURE_HSTS_PRELOAD, settings.SESSION_COOKIE_SECURE, settings.CSRF_COOKIE_SECURE, settings.SECURE_PROXY_SSL_HEADER)",
        ],
        capture_output=True,
        text=True,
        env=environment,
    )

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == "True 31536000 True True True True ('HTTP_X_FORWARDED_PROTO', 'https')"
