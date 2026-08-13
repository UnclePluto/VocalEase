from .base import *  # noqa: F403

SECRET_KEY = "vocaease-test-suite-key-only-2026-08"
AUTH_WEB_ALLOWED_ORIGINS = ["https://app.vocaease.test"]
DATABASES = {"default": {"ENGINE": "django.db.backends.sqlite3", "NAME": ":memory:"}}
CACHES = {"default": {"BACKEND": "django.core.cache.backends.locmem.LocMemCache"}}
PASSWORD_HASHERS = ["django.contrib.auth.hashers.MD5PasswordHasher"]
