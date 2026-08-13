from .base import *  # noqa: F403

SECRET_KEY = "vocaease-postgresql-test-suite-key-only-2026-08"
CACHES = {"default": {"BACKEND": "django.core.cache.backends.locmem.LocMemCache"}}
PASSWORD_HASHERS = ["django.contrib.auth.hashers.MD5PasswordHasher"]
MEDIA_BACKEND = "local"
MEDIA_ENVIRONMENT = "test"
