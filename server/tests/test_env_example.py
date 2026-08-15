from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def test_env_example_covers_runtime_security_storage_analysis_and_export_settings():
    keys = {
        line.split("=", 1)[0]
        for line in (ROOT / ".env.example").read_text().splitlines()
        if line and not line.startswith("#") and "=" in line
    }

    assert {
        "DJANGO_SECRET_KEY",
        "DJANGO_DEBUG",
        "DJANGO_ALLOWED_HOSTS",
        "AUTH_WEB_ALLOWED_ORIGINS",
        "POSTGRES_DB",
        "POSTGRES_USER",
        "POSTGRES_PASSWORD",
        "POSTGRES_HOST",
        "POSTGRES_PORT",
        "REDIS_URL",
        "CELERY_BROKER_URL",
        "CELERY_RESULT_BACKEND",
        "ANALYSIS_TASK_LEASE_SECONDS",
        "MEDIA_BACKEND",
        "MEDIA_ENVIRONMENT",
        "MEDIA_LOCAL_ROOT",
        "MEDIA_UPLOAD_GRANT_TTL_SECONDS",
        "MEDIA_PRIVATE_URL_TTL_SECONDS",
        "MEDIA_AUDIO_MAX_BYTES",
        "MEDIA_VIDEO_MAX_BYTES",
        "QINIU_ACCESS_KEY",
        "QINIU_SECRET_KEY",
        "QINIU_BUCKET",
        "QINIU_DOMAIN",
        "QINIU_CALLBACK_URL",
        "ANALYTICS_SYNC_EXPORT_LIMIT",
        "ANALYTICS_EXPORT_TTL_SECONDS",
        "ANALYTICS_EXPORT_LEASE_SECONDS",
        "ANALYTICS_EXPORT_HEARTBEAT_SECONDS",
        "ANALYTICS_EXPORT_MAX_ATTEMPTS",
        "WEB_MEDIA_ORIGIN",
    } <= keys
