from .base import *  # noqa: F403

SECRET_KEY = "vocaease-local-development-key-only-2026"
DEBUG = True
AUTH_REFRESH_COOKIE_SECURE = False
AUTH_WEB_ALLOWED_ORIGINS = [
    origin.strip().rstrip("/")
    for origin in os.getenv(
        "AUTH_WEB_ALLOWED_ORIGINS",
        "http://localhost,http://127.0.0.1,http://localhost:3000,http://127.0.0.1:3000",
    ).split(",")
    if origin.strip()
]
MEDIA_BACKEND = os.getenv("MEDIA_BACKEND", "local")
MEDIA_ENVIRONMENT = os.getenv("MEDIA_ENVIRONMENT", "local")
