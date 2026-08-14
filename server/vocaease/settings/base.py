import os
from datetime import timedelta
from pathlib import Path

from django.core.exceptions import ImproperlyConfigured

BASE_DIR = Path(__file__).resolve().parents[2]

SECRET_KEY = os.getenv("DJANGO_SECRET_KEY", "")
settings_module = os.getenv("DJANGO_SETTINGS_MODULE", "")
if not SECRET_KEY and settings_module not in {
    "vocaease.settings.local",
    "vocaease.settings.test",
    "vocaease.settings.postgresql_test",
}:
    raise ImproperlyConfigured("生产环境必须配置 DJANGO_SECRET_KEY")
DEBUG = os.getenv("DJANGO_DEBUG", "false").lower() == "true"
ALLOWED_HOSTS = [host for host in os.getenv("DJANGO_ALLOWED_HOSTS", "localhost,127.0.0.1").split(",") if host]

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "apps.accounts",
    "apps.audit",
    "apps.doctors",
    "apps.patients",
    "apps.media",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
    "common.middleware.RequestIdMiddleware",
]

ROOT_URLCONF = "vocaease.urls"
TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.postgresql",
        "NAME": os.getenv("POSTGRES_DB", "vocaease"),
        "USER": os.getenv("POSTGRES_USER", "vocaease"),
        "PASSWORD": os.getenv("POSTGRES_PASSWORD", "vocaease"),
        "HOST": os.getenv("POSTGRES_HOST", "localhost"),
        "PORT": os.getenv("POSTGRES_PORT", "5432"),
    }
}

CACHES = {
    "default": {
        "BACKEND": "django.core.cache.backends.redis.RedisCache",
        "LOCATION": os.getenv("REDIS_URL", "redis://localhost:6379/0"),
    }
}

AUTH_USER_MODEL = "accounts.User"

AUTH_REFRESH_COOKIE_NAME = "refresh_token"
AUTH_REFRESH_CSRF_COOKIE_NAME = "refresh_csrf_token"
AUTH_REFRESH_COOKIE_SECURE = not DEBUG
AUTH_WEB_ALLOWED_ORIGINS = [
    origin.strip().rstrip("/")
    for origin in os.getenv("AUTH_WEB_ALLOWED_ORIGINS", "").split(",")
    if origin.strip()
]

SIMPLE_JWT = {
    "ACCESS_TOKEN_LIFETIME": timedelta(minutes=15),
    "REFRESH_TOKEN_LIFETIME": timedelta(days=1),
}

REST_FRAMEWORK = {
    "EXCEPTION_HANDLER": "common.api.errors.exception_handler",
    "DEFAULT_AUTHENTICATION_CLASSES": [
        "apps.accounts.tokens.ActiveUserJWTAuthentication",
    ],
    "DEFAULT_PERMISSION_CLASSES": [
        "rest_framework.permissions.IsAuthenticated",
        "common.api.permissions.MustChangePasswordPermission",
    ],
    "DEFAULT_THROTTLE_CLASSES": [
        "rest_framework.throttling.AnonRateThrottle",
        "rest_framework.throttling.UserRateThrottle",
    ],
    "DEFAULT_THROTTLE_RATES": {
        "anon": "60/min",
        "user": "120/min",
        "auth_login": "10/min",
        "auth_refresh": "30/min",
        "auth_change_password": "10/min",
        "auth_logout": "30/min",
        "auth_reset_password": "5/min",
        "credential_upload": "10/min",
    },
}

CELERY_BROKER_URL = os.getenv("CELERY_BROKER_URL", os.getenv("REDIS_URL", "redis://localhost:6379/0"))
CELERY_RESULT_BACKEND = os.getenv("CELERY_RESULT_BACKEND", os.getenv("REDIS_URL", "redis://localhost:6379/0"))
CELERY_ACCEPT_CONTENT = ["json"]
CELERY_TASK_SERIALIZER = "json"
CELERY_RESULT_SERIALIZER = "json"

LANGUAGE_CODE = "zh-hans"
TIME_ZONE = "Asia/Shanghai"
USE_I18N = True
USE_TZ = True
STATIC_URL = "static/"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

MEDIA_BACKEND = os.getenv("MEDIA_BACKEND", "qiniu")
MEDIA_ENVIRONMENT = os.getenv("MEDIA_ENVIRONMENT", "production")
MEDIA_LOCAL_ROOT = os.getenv("MEDIA_LOCAL_ROOT", str(BASE_DIR / "private-media"))
MEDIA_UPLOAD_GRANT_TTL_SECONDS = int(os.getenv("MEDIA_UPLOAD_GRANT_TTL_SECONDS", "900"))
MEDIA_PRIVATE_URL_TTL_SECONDS = int(os.getenv("MEDIA_PRIVATE_URL_TTL_SECONDS", "600"))
MEDIA_AUDIO_MAX_BYTES = int(os.getenv("MEDIA_AUDIO_MAX_BYTES", str(50 * 1024 * 1024)))
MEDIA_VIDEO_MAX_BYTES = int(os.getenv("MEDIA_VIDEO_MAX_BYTES", str(500 * 1024 * 1024)))
MEDIA_OTHER_MAX_BYTES = int(os.getenv("MEDIA_OTHER_MAX_BYTES", str(10 * 1024 * 1024)))
MEDIA_SCANNER_MAX_MARKERS = min(int(os.getenv("MEDIA_SCANNER_MAX_MARKERS", "1000")), 1000)
MEDIA_SCANNER_MAX_MARKER_BYTES = min(int(os.getenv("MEDIA_SCANNER_MAX_MARKER_BYTES", str(64 * 1024))), 64 * 1024)
QINIU_ACCESS_KEY = os.getenv("QINIU_ACCESS_KEY", "")
QINIU_SECRET_KEY = os.getenv("QINIU_SECRET_KEY", "")
QINIU_BUCKET = os.getenv("QINIU_BUCKET", "")
QINIU_DOMAIN = os.getenv("QINIU_DOMAIN", "")
QINIU_CALLBACK_URL = os.getenv("QINIU_CALLBACK_URL", "")
QINIU_UPLOAD_URL = os.getenv("QINIU_UPLOAD_URL", "https://up.qiniup.com")
QINIU_RS_HOST = os.getenv("QINIU_RS_HOST", "https://rs.qiniu.com")
QINIU_STAT_TIMEOUT_SECONDS = float(os.getenv("QINIU_STAT_TIMEOUT_SECONDS", "5"))

if settings_module not in {"vocaease.settings.local", "vocaease.settings.test", "vocaease.settings.postgresql_test"}:
    if MEDIA_BACKEND != "qiniu" or not all((QINIU_ACCESS_KEY, QINIU_SECRET_KEY, QINIU_BUCKET, QINIU_DOMAIN, QINIU_CALLBACK_URL)):
        raise ImproperlyConfigured("生产环境必须配置七牛私有空间凭据、域名和回调地址")

LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "filters": {"request_id": {"()": "common.middleware.RequestIdLogFilter"}},
    "formatters": {
        "json": {
            "format": '{"time":"%(asctime)s","level":"%(levelname)s","logger":"%(name)s","message":"%(message)s","request_id":"%(request_id)s"}',
        }
    },
    "handlers": {
        "console": {"class": "logging.StreamHandler", "filters": ["request_id"], "formatter": "json"}
    },
    "root": {"handlers": ["console"], "level": os.getenv("LOG_LEVEL", "INFO")},
}
