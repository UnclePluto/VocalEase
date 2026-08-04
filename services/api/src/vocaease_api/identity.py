import hashlib
import secrets
from datetime import UTC, datetime, timedelta
from typing import Annotated

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pwdlib import PasswordHash
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from vocaease_api.database import Account, LoginSession, database_session
from vocaease_api.settings import Settings

INITIAL_PARTICIPANT_PASSWORD = "88888888"
password_hash = PasswordHash.recommended()
bearer = HTTPBearer(auto_error=False)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def bootstrap_admin(session: Session, settings: Settings) -> None:
    existing = session.scalar(
        select(Account).where(Account.username == settings.bootstrap_admin_username)
    )
    if existing is not None:
        return
    session.add(
        Account(
            role="admin",
            username=settings.bootstrap_admin_username,
            phone=None,
            password_hash=password_hash.hash(settings.bootstrap_admin_password),
            must_change_password=False,
            active=True,
        )
    )
    session.commit()


def verify_credentials(account: Account | None, password: str) -> Account:
    if (
        account is None
        or not account.active
        or not password_hash.verify(password, account.password_hash)
    ):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "账号或密码错误")
    return account


def issue_session(session: Session, account: Account, settings: Settings) -> str:
    token = secrets.token_urlsafe(32)
    session.add(
        LoginSession(
            account_id=account.id,
            token_hash=hash_token(token),
            expires_at=datetime.now(UTC) + timedelta(hours=settings.session_hours),
            revoked_at=None,
        )
    )
    session.commit()
    return token


def revoke_account_sessions(session: Session, account_id: object) -> None:
    session.execute(
        update(LoginSession)
        .where(LoginSession.account_id == account_id, LoginSession.revoked_at.is_(None))
        .values(revoked_at=datetime.now(UTC))
    )


def current_account(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    session: Annotated[Session, Depends(database_session)],
) -> Account:
    if credentials is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "需要登录")
    login_session = session.scalar(
        select(LoginSession).where(
            LoginSession.token_hash == hash_token(credentials.credentials),
            LoginSession.revoked_at.is_(None),
            LoginSession.expires_at > datetime.now(UTC),
        )
    )
    if login_session is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "登录已失效")
    account = session.get(Account, login_session.account_id)
    if account is None or not account.active:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "登录已失效")
    request.state.login_session = login_session
    return account


CurrentAccount = Annotated[Account, Depends(current_account)]
DatabaseSession = Annotated[Session, Depends(database_session)]


def require_role(account: Account, role: str) -> None:
    if account.role != role:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "没有访问权限")
