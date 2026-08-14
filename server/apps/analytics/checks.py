from django.conf import settings
from django.core.checks import Error, register


@register()
def analytics_export_settings_check(app_configs, **kwargs):
    del app_configs, kwargs
    errors = []
    sync_limit = getattr(settings, "ANALYTICS_SYNC_EXPORT_LIMIT", 0)
    ttl = getattr(settings, "ANALYTICS_EXPORT_TTL_SECONDS", 0)
    lease = getattr(settings, "ANALYTICS_EXPORT_LEASE_SECONDS", 0)
    heartbeat = getattr(settings, "ANALYTICS_EXPORT_HEARTBEAT_SECONDS", 0)
    max_attempts = getattr(settings, "ANALYTICS_EXPORT_MAX_ATTEMPTS", 0)
    private_ttl = getattr(settings, "MEDIA_PRIVATE_URL_TTL_SECONDS", 0)
    if not isinstance(sync_limit, int) or not 1 <= sync_limit <= 1000:
        errors.append(Error("同步导出阈值必须在 1 到 1000 之间", id="analytics.E001"))
    if not all(isinstance(value, int) and value > 0 for value in (ttl, lease, heartbeat, private_ttl)):
        errors.append(Error("导出 TTL、租约、心跳和下载 TTL 必须为正整数", id="analytics.E002"))
    if all(isinstance(value, int) for value in (ttl, lease, heartbeat, private_ttl)) and not (
        heartbeat < lease < ttl and private_ttl <= ttl
    ):
        errors.append(Error("导出时限必须满足 heartbeat < lease < TTL 且下载 TTL 不大于导出 TTL", id="analytics.E003"))
    if not isinstance(max_attempts, int) or not 1 <= max_attempts <= 4:
        errors.append(Error("导出最大尝试次数必须在 1 到数据库上限 4 之间", id="analytics.E004"))
    return errors
