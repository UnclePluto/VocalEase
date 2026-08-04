from collections.abc import Generator
from datetime import UTC, datetime
from enum import StrEnum
from uuid import UUID, uuid4

from alembic import command
from alembic.config import Config
from fastapi import Request
from sqlalchemy import (
    Boolean,
    DateTime,
    Enum,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    create_engine,
    inspect,
)
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
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    account: Mapped[Account] = relationship(back_populates="participant")


class LoginSession(Base):
    __tablename__ = "login_sessions"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    account_id: Mapped[UUID] = mapped_column(ForeignKey("accounts.id", ondelete="CASCADE"))
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Song(Base):
    __tablename__ = "songs"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    title: Mapped[str] = mapped_column(String(200))
    artist: Mapped[str] = mapped_column(String(200))
    cover_media_id: Mapped[UUID | None] = mapped_column(ForeignKey("media_files.id"))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class MediaFile(Base):
    __tablename__ = "media_files"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    storage_key: Mapped[str] = mapped_column(String(255), unique=True)
    content_type: Mapped[str] = mapped_column(String(100))
    size_bytes: Mapped[int] = mapped_column(Integer)
    sha256: Mapped[str] = mapped_column(String(64))
    purpose: Mapped[str] = mapped_column(String(40))


class BackingTrackVersion(Base):
    __tablename__ = "backing_track_versions"
    __table_args__ = (UniqueConstraint("song_id", "version", name="uq_backing_track_song_version"),)

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    song_id: Mapped[UUID] = mapped_column(ForeignKey("songs.id"), index=True)
    version: Mapped[int] = mapped_column(Integer)
    source_media_id: Mapped[UUID] = mapped_column(ForeignKey("media_files.id"))
    normalized_media_id: Mapped[UUID] = mapped_column(ForeignKey("media_files.id"))
    duration_ms: Mapped[int] = mapped_column(Integer)
    sample_rate: Mapped[int] = mapped_column(Integer)
    channels: Mapped[int] = mapped_column(Integer)
    review_status: Mapped[str] = mapped_column(String(20), default="approved")
    source_kind: Mapped[str] = mapped_column(String(30), default="uploaded_backing")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class LyricVersion(Base):
    __tablename__ = "lyric_versions"
    __table_args__ = (
        UniqueConstraint("backing_track_id", "version", name="uq_lyric_track_version"),
    )

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    backing_track_id: Mapped[UUID] = mapped_column(
        ForeignKey("backing_track_versions.id"), index=True
    )
    version: Mapped[int] = mapped_column(Integer)
    lrc_text: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class SongPublication(Base):
    __tablename__ = "song_publications"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    song_id: Mapped[UUID] = mapped_column(ForeignKey("songs.id"), index=True)
    backing_track_id: Mapped[UUID] = mapped_column(ForeignKey("backing_track_versions.id"))
    lyric_version_id: Mapped[UUID] = mapped_column(ForeignKey("lyric_versions.id"))
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    published_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


def initialize_database(settings: Settings) -> sessionmaker[Session]:
    engine = create_engine(settings.database_url, pool_pre_ping=True)
    config = Config(settings.migration_config)
    config.set_main_option("sqlalchemy.url", settings.database_url)
    existing_tables = set(inspect(engine).get_table_names())
    if "accounts" in existing_tables and "alembic_version" not in existing_tables:
        command.stamp(config, "20260804_01")
    command.upgrade(config, "head")
    return sessionmaker(engine, expire_on_commit=False)


def database_session(request: Request) -> Generator[Session]:
    factory: sessionmaker[Session] = request.app.state.session_factory
    with factory() as session:
        yield session
