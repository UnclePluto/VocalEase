from datetime import datetime
from uuid import UUID

from fastapi import APIRouter, Query
from pydantic import BaseModel
from sqlalchemy import select

from vocaease_api.database import AccountRole
from vocaease_api.identity import CurrentAccount, DatabaseSession, require_role
from vocaease_api.singing_models import AuditEvent

router = APIRouter(prefix="/api/v1")


class AuditEventResponse(BaseModel):
    id: UUID
    actor_account_id: UUID | None
    action: str
    object_type: str
    object_id: UUID | None
    detail: dict
    created_at: datetime


@router.get("/admin/audit-events", response_model=list[AuditEventResponse])
def list_audit_events(
    account: CurrentAccount,
    session: DatabaseSession,
    actor_account_id: UUID | None = None,
    action: str | None = Query(default=None, max_length=80),
    object_type: str | None = Query(default=None, max_length=40),
    created_from: datetime | None = None,
    created_to: datetime | None = None,
) -> list[AuditEventResponse]:
    require_role(account, AccountRole.ADMIN)
    query = select(AuditEvent).order_by(AuditEvent.created_at.desc()).limit(500)
    if actor_account_id is not None:
        query = query.where(AuditEvent.actor_account_id == actor_account_id)
    if action:
        query = query.where(AuditEvent.action == action)
    if object_type:
        query = query.where(AuditEvent.object_type == object_type)
    if created_from:
        query = query.where(AuditEvent.created_at >= created_from)
    if created_to:
        query = query.where(AuditEvent.created_at <= created_to)
    return [
        AuditEventResponse(
            id=event.id,
            actor_account_id=event.actor_account_id,
            action=event.action,
            object_type=event.object_type,
            object_id=event.object_id,
            detail=event.detail,
            created_at=event.created_at,
        )
        for event in session.scalars(query).all()
    ]
