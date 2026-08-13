from collections.abc import Mapping
from typing import Any

from django.db import models

from .models import AuditLog


SENSITIVE_FIELDS = {"password", "old_password", "new_password", "refresh", "token"}


def redact(changes: Mapping[str, Any]) -> dict[str, Any]:
    return {
        key: "[REDACTED]" if key.lower() in SENSITIVE_FIELDS else value
        for key, value in changes.items()
    }


def record(*, actor, action: str, target: models.Model | None, changes: Mapping[str, Any], request_id: str) -> AuditLog:
    return AuditLog.objects.create(
        actor=actor,
        action=action,
        target_type=target._meta.label if target else "",
        target_id=getattr(target, "pk", None),
        changes=redact(changes),
        request_id=request_id,
    )
