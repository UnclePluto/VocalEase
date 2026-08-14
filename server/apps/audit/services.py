import hashlib
import json
import re
from collections.abc import Mapping
from typing import Any

from django.db import models

from .models import AuditLog


SENSITIVE_FIELDS = {
    "password",
    "passwords",
    "oldpassword",
    "newpassword",
    "token",
    "tokens",
    "refresh",
    "refreshtoken",
    "refreshtokens",
    "accesstoken",
    "accesstokens",
    "authorization",
    "signature",
    "signatures",
    "privateurl",
    "privateurls",
    "sha256",
    "hash",
    "hashes",
    "etag",
    "etags",
}
MEDICAL_NOTE_FIELDS = {"medicalnote", "medicalnotes", "clinicalnote", "clinicalnotes"}
PHONE_FIELDS = {"phone", "mobile", "phonenumber", "mobilenumber"}


def normalize_field_name(key: Any) -> str:
    return re.sub(r"[^a-z0-9]", "", str(key).lower())


def medical_content_placeholder(value: Any) -> str:
    serialized = json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
    digest = hashlib.sha256(serialized.encode("utf-8")).hexdigest()[:12]
    return f"[MEDICAL_CONTENT_REDACTED sha256:{digest}]"


def redact_value(value: Any) -> Any:
    if isinstance(value, Mapping):
        result = {}
        for key, nested_value in value.items():
            normalized = normalize_field_name(key)
            if (
                normalized in SENSITIVE_FIELDS
                or normalized.endswith("password")
                or normalized.endswith("passwords")
                or normalized.endswith("token")
                or normalized.endswith("tokens")
                or normalized.endswith("signature")
                or normalized.endswith("signatures")
                or normalized.endswith("privateurl")
                or normalized.endswith("privateurls")
                or normalized in {"sha256", "hash", "hashes", "etag", "etags"}
                or "authorization" in normalized
            ):
                result[key] = "[REDACTED]"
            elif normalized in MEDICAL_NOTE_FIELDS or normalized.endswith(
                ("medicalnote", "medicalnotes", "clinicalnote", "clinicalnotes")
            ):
                result[key] = medical_content_placeholder(nested_value)
            elif normalized in PHONE_FIELDS or normalized.endswith(
                ("phone", "mobile", "phonenumber", "mobilenumber")
            ):
                result[key] = "[PHONE_REDACTED]"
            else:
                result[key] = redact_value(nested_value)
        return result
    if isinstance(value, (list, tuple)):
        return [redact_value(item) for item in value]
    return value


def redact(changes: Mapping[str, Any]) -> dict[str, Any]:
    return redact_value(changes)


def record(*, actor, action: str, target: models.Model | None, changes: Mapping[str, Any], request_id: str) -> AuditLog:
    return AuditLog.objects.create(
        actor=actor,
        action=action,
        target_type=target._meta.label if target else "",
        target_id=getattr(target, "pk", None),
        changes=redact(changes),
        request_id=request_id,
    )
