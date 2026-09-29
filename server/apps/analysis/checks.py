from django.conf import settings
from django.core.checks import Error, register


@register()
def check_analysis_task_lease(app_configs, **kwargs):
    value = getattr(settings, "ANALYSIS_TASK_LEASE_SECONDS", None)
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        return [
            Error(
                "ANALYSIS_TASK_LEASE_SECONDS 必须是大于或等于 1 的整数",
                id="analysis.E001",
            )
        ]
    return []
