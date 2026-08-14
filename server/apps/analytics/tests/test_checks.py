from django.core.checks import run_checks


def _analytics_error_ids():
    return {message.id for message in run_checks() if message.id.startswith("analytics.")}


def test_analytics_export_runtime_boundaries_are_valid_by_default():
    assert _analytics_error_ids() == set()


def test_analytics_export_runtime_boundaries_reject_invalid_relationships(settings):
    settings.ANALYTICS_EXPORT_TTL_SECONDS = 10
    settings.ANALYTICS_EXPORT_LEASE_SECONDS = 20
    settings.ANALYTICS_EXPORT_HEARTBEAT_SECONDS = 0
    settings.ANALYTICS_EXPORT_MAX_ATTEMPTS = 0

    errors = _analytics_error_ids()

    assert {"analytics.E002", "analytics.E003", "analytics.E004"} <= errors
