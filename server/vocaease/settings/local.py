from .base import *  # noqa: F403

SECRET_KEY = "vocaease-local-development-key-only-2026"
DEBUG = True
AUTH_REFRESH_COOKIE_SECURE = False
AUTH_WEB_ALLOWED_ORIGINS = ["http://localhost", "http://127.0.0.1"]
