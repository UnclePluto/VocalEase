from uuid import UUID

from sqlalchemy.orm import Session

from vocaease_api.singing_models import AuditEvent


def record_audit(
    session: Session,
    *,
    actor_account_id: UUID | None,
    action: str,
    object_type: str,
    object_id: UUID | None,
    detail: dict | None = None,
) -> None:
    session.add(
        AuditEvent(
            actor_account_id=actor_account_id,
            action=action,
            object_type=object_type,
            object_id=object_id,
            detail=detail or {},
        )
    )
