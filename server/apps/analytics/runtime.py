from dataclasses import dataclass

from django.conf import settings


class ExportRuntimeConfigurationError(RuntimeError):
    pass


@dataclass(frozen=True)
class ExportRuntimeConfig:
    sync_limit: int
    ttl_seconds: int
    lease_seconds: int
    heartbeat_seconds: int
    max_attempts: int
    private_url_ttl_seconds: int


def get_export_runtime_config() -> ExportRuntimeConfig:
    values = ExportRuntimeConfig(
        sync_limit=settings.ANALYTICS_SYNC_EXPORT_LIMIT,
        ttl_seconds=settings.ANALYTICS_EXPORT_TTL_SECONDS,
        lease_seconds=settings.ANALYTICS_EXPORT_LEASE_SECONDS,
        heartbeat_seconds=settings.ANALYTICS_EXPORT_HEARTBEAT_SECONDS,
        max_attempts=settings.ANALYTICS_EXPORT_MAX_ATTEMPTS,
        private_url_ttl_seconds=settings.MEDIA_PRIVATE_URL_TTL_SECONDS,
    )
    integers = tuple(values.__dict__.values())
    if any(type(value) is not int for value in integers):
        raise ExportRuntimeConfigurationError("导出运行参数必须为整数")
    if not 1 <= values.sync_limit <= 1000:
        raise ExportRuntimeConfigurationError("同步导出阈值不合法")
    if any(value <= 0 for value in integers[1:]):
        raise ExportRuntimeConfigurationError("导出运行参数必须为正数")
    if not (
        values.heartbeat_seconds * 2 < values.lease_seconds < values.ttl_seconds
        and values.private_url_ttl_seconds <= values.ttl_seconds
        and 1 <= values.max_attempts <= 4
    ):
        raise ExportRuntimeConfigurationError("导出运行参数关系不安全")
    return values
