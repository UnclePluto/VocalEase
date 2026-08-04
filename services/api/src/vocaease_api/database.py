from collections.abc import Generator
from datetime import UTC, datetime
from enum import StrEnum
from uuid import UUID, uuid4

from alembic import command
from alembic.config import Config
from fastapi import Request
from sqlalchemy import Boolean, DateTime, Enum, ForeignKey, String, create_engine, inspect
from sqlalchemy.dialects.postgresql import UUID as PostgresUUID
from sqlalchemy.orm import (
    DeclarativeBase,
    Mapped,
    Session,
    mapped_column,
    relationship,
    sessionmaker,
)

from vocaease_api.settings import Settings


class Base(DeclarativeBase):
    pass


class AccountRole(StrEnum):
    ADMIN = "admin"
    PARTICIPANT = "participant"


class Account(Base):
    __tablename__ = "accounts"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    role: Mapped[AccountRole] = mapped_column(
        Enum(
            AccountRole,
            native_enum=False,
            values_callable=lambda values: [item.value for item in values],
        ),
        index=True,
    )
    username: Mapped[str | None] = mapped_column(String(80), unique=True)
    phone: Mapped[str | None] = mapped_column(String(20), unique=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    must_change_password: Mapped[bool] = mapped_column(Boolean, default=False)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )

    participant: Mapped["Participant | None"] = relationship(back_populates="account")


class Participant(Base):
    __tablename__ = "participants"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    account_id: Mapped[UUID] = mapped_column(
        ForeignKey("accounts.id", ondelete="CASCADE"), unique=True
    )
    name: Mapped[str] = mapped_column(String(100))
    research_code: Mapped[str] = mapped_column(String(80), unique=True, index=True)

    account: Mapped[Account] = relationship(back_populates="participant")


class LoginSession(Base):
    __tablename__ = "login_sessions"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    account_id: Mapped[UUID] = mapped_column(ForeignKey("accounts.id", ondelete="CASCADE"))
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


def initialize_database(settings: Settings) -> sessionmaker[Session]:
    engine = create_engine(settings.database_url, pool_pre_ping=True)
    config = Config(settings.migration_config)
    config.set_main_option("sqlalchemy.url", settings.database_url)
    existing_tables = set(inspect(engine).get_table_names())
    if "accounts" in existing_tables and "alembic_version" not in existing_tables:
        command.stamp(config, "head")
    else:
        command.upgrade(config, "head")
    return sessionmaker(engine, expire_on_commit=False)


def database_session(request: Request) -> Generator[Session]:
    factory: sessionmaker[Session] = request.app.state.session_factory
    with factory() as session:
        yield session
