from uuid import UUID

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError

from vocaease_api.database import Account, Participant
from vocaease_api.identity import (
    INITIAL_PARTICIPANT_PASSWORD,
    CurrentAccount,
    DatabaseSession,
    issue_session,
    password_hash,
    require_role,
    revoke_account_sessions,
    verify_credentials,
)
from vocaease_api.settings import Settings

router = APIRouter(prefix="/api/v1")


class AdminLoginRequest(BaseModel):
    username: str
    password: str


class ParticipantLoginRequest(BaseModel):
    phone: str
    password: str


class LoginResponse(BaseModel):
    access_token: str
    must_change_password: bool


class CreateParticipantRequest(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    phone: str = Field(pattern=r"^1\d{10}$")
    research_code: str = Field(min_length=1, max_length=80)


class UpdateParticipantRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    phone: str | None = Field(default=None, pattern=r"^1\d{10}$")
    research_code: str | None = Field(default=None, min_length=1, max_length=80)
    active: bool | None = None


class ParticipantResponse(BaseModel):
    id: UUID
    account_id: UUID
    name: str
    phone: str
    research_code: str
    active: bool
    must_change_password: bool


class ChangePasswordRequest(BaseModel):
    current_password: str
    new_password: str = Field(min_length=10, max_length=128)


def participant_response(participant: Participant) -> ParticipantResponse:
    account = participant.account
    return ParticipantResponse(
        id=participant.id,
        account_id=account.id,
        name=participant.name,
        phone=account.phone or "",
        research_code=participant.research_code,
        active=account.active,
        must_change_password=account.must_change_password,
    )


@router.post("/auth/admin/login", response_model=LoginResponse)
def admin_login(payload: AdminLoginRequest, session: DatabaseSession) -> LoginResponse:
    account = verify_credentials(
        session.scalar(select(Account).where(Account.username == payload.username)),
        payload.password,
    )
    require_role(account, "admin")
    return LoginResponse(
        access_token=issue_session(session, account, Settings()), must_change_password=False
    )


@router.post("/auth/participant/login", response_model=LoginResponse)
def participant_login(payload: ParticipantLoginRequest, session: DatabaseSession) -> LoginResponse:
    account = verify_credentials(
        session.scalar(select(Account).where(Account.phone == payload.phone)), payload.password
    )
    require_role(account, "participant")
    return LoginResponse(
        access_token=issue_session(session, account, Settings()),
        must_change_password=account.must_change_password,
    )


@router.post("/auth/participant/change-password", response_model=LoginResponse)
def change_password(
    payload: ChangePasswordRequest, account: CurrentAccount, session: DatabaseSession
) -> LoginResponse:
    require_role(account, "participant")
    verify_credentials(account, payload.current_password)
    if payload.new_password == INITIAL_PARTICIPANT_PASSWORD:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "新密码不能使用初始密码")
    account.password_hash = password_hash.hash(payload.new_password)
    account.must_change_password = False
    revoke_account_sessions(session, account.id)
    session.commit()
    return LoginResponse(
        access_token=issue_session(session, account, Settings()), must_change_password=False
    )


@router.get("/participant/home")
def participant_home(account: CurrentAccount) -> dict[str, str]:
    require_role(account, "participant")
    if account.must_change_password:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "必须先修改初始密码")
    return {"status": "ready"}


@router.get("/admin/me")
def admin_me(account: CurrentAccount) -> dict[str, str]:
    require_role(account, "admin")
    return {"username": account.username or ""}


@router.get("/admin/participants", response_model=list[ParticipantResponse])
def list_participants(
    account: CurrentAccount, session: DatabaseSession
) -> list[ParticipantResponse]:
    require_role(account, "admin")
    participants = session.scalars(select(Participant).order_by(Participant.research_code)).all()
    return [participant_response(participant) for participant in participants]


@router.post(
    "/admin/participants", response_model=ParticipantResponse, status_code=status.HTTP_201_CREATED
)
def create_participant(
    payload: CreateParticipantRequest, account: CurrentAccount, session: DatabaseSession
) -> ParticipantResponse:
    require_role(account, "admin")
    duplicate = session.scalar(
        select(Account.id)
        .outerjoin(Participant, Participant.account_id == Account.id)
        .where(
            or_(Account.phone == payload.phone, Participant.research_code == payload.research_code)
        )
    )
    if duplicate is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "手机号或研究编号已存在")
    participant = Participant(
        account=Account(
            role="participant",
            username=None,
            phone=payload.phone,
            password_hash=password_hash.hash(INITIAL_PARTICIPANT_PASSWORD),
            must_change_password=True,
            active=True,
        ),
        name=payload.name,
        research_code=payload.research_code,
    )
    session.add(participant)
    session.commit()
    return participant_response(participant)


def find_participant(participant_id: UUID, session: DatabaseSession) -> Participant:
    participant = session.get(Participant, participant_id)
    if participant is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "参与者不存在")
    return participant


@router.patch("/admin/participants/{participant_id}", response_model=ParticipantResponse)
def update_participant(
    participant_id: UUID,
    payload: UpdateParticipantRequest,
    account: CurrentAccount,
    session: DatabaseSession,
) -> ParticipantResponse:
    require_role(account, "admin")
    participant = find_participant(participant_id, session)
    values = payload.model_dump(exclude_unset=True)
    if "name" in values:
        participant.name = values["name"]
    if "research_code" in values:
        participant.research_code = values["research_code"]
    if "phone" in values:
        participant.account.phone = values["phone"]
    if "active" in values:
        participant.account.active = values["active"]
        if not values["active"]:
            revoke_account_sessions(session, participant.account.id)
    try:
        session.commit()
    except IntegrityError as error:
        session.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, "手机号或研究编号已存在") from error
    return participant_response(participant)


@router.post(
    "/admin/participants/{participant_id}/reset-password",
    status_code=status.HTTP_204_NO_CONTENT,
)
def reset_participant_password(
    participant_id: UUID, account: CurrentAccount, session: DatabaseSession
) -> None:
    require_role(account, "admin")
    participant = find_participant(participant_id, session)
    participant.account.password_hash = password_hash.hash(INITIAL_PARTICIPANT_PASSWORD)
    participant.account.must_change_password = True
    revoke_account_sessions(session, participant.account.id)
    session.commit()
