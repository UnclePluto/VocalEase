from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import JSON, BigInteger, Boolean, DateTime, ForeignKey, Integer, String
from sqlalchemy.dialects.postgresql import UUID as PostgresUUID
from sqlalchemy.orm import Mapped, mapped_column

from vocaease_api.database import Base


class SingingSession(Base):
    __tablename__ = "singing_sessions"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    participant_id: Mapped[UUID] = mapped_column(ForeignKey("participants.id"), index=True)
    song_id: Mapped[UUID] = mapped_column(ForeignKey("songs.id"))
    backing_track_id: Mapped[UUID] = mapped_column(ForeignKey("backing_track_versions.id"))
    lyric_version_id: Mapped[UUID] = mapped_column(ForeignKey("lyric_versions.id"))
    status: Mapped[str] = mapped_column(String(30), default="created", index=True)
    used_headphones: Mapped[bool] = mapped_column(Boolean)
    headphone_risk_confirmed: Mapped[bool] = mapped_column(Boolean, default=False)
    device_snapshot: Mapped[dict] = mapped_column(JSON)
    pre_duration_ms: Mapped[int] = mapped_column(Integer, default=3_000)
    song_duration_ms: Mapped[int] = mapped_column(Integer)
    post_duration_ms: Mapped[int] = mapped_column(Integer, default=3_000)
    accompaniment_start_frame: Mapped[int | None] = mapped_column(BigInteger)
    audio_start_monotonic_ns: Mapped[int | None] = mapped_column(BigInteger)
    accompaniment_start_monotonic_ns: Mapped[int | None] = mapped_column(BigInteger)
    recorded_frame_count: Mapped[int | None] = mapped_column(BigInteger)
    interruption_reason: Mapped[str | None] = mapped_column(String(60))
    raw_voice_media_id: Mapped[UUID | None] = mapped_column(ForeignKey("media_files.id"))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class VoiceUpload(Base):
    __tablename__ = "voice_uploads"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    singing_session_id: Mapped[UUID] = mapped_column(
        ForeignKey("singing_sessions.id"), unique=True, index=True
    )
    status: Mapped[str] = mapped_column(String(30), default="pending")
    expected_chunks: Mapped[int] = mapped_column(Integer)
    total_bytes: Mapped[int] = mapped_column(BigInteger)
    total_sha256: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )
    verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class VoiceUploadChunk(Base):
    __tablename__ = "voice_upload_chunks"

    upload_id: Mapped[UUID] = mapped_column(
        ForeignKey("voice_uploads.id", ondelete="CASCADE"), primary_key=True
    )
    chunk_number: Mapped[int] = mapped_column(Integer, primary_key=True)
    size_bytes: Mapped[int] = mapped_column(Integer)
    sha256: Mapped[str] = mapped_column(String(64))
    storage_key: Mapped[str] = mapped_column(String(255), unique=True)


class AudioQualityReportRecord(Base):
    __tablename__ = "audio_quality_reports"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    singing_session_id: Mapped[UUID] = mapped_column(ForeignKey("singing_sessions.id"), index=True)
    source: Mapped[str] = mapped_column(String(20))
    algorithm_version: Mapped[str] = mapped_column(String(40))
    status: Mapped[str] = mapped_column(String(20))
    metrics: Mapped[dict] = mapped_column(JSON)
    generated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class SessionMediaAnalysis(Base):
    __tablename__ = "session_media_analyses"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    singing_session_id: Mapped[UUID] = mapped_column(
        ForeignKey("singing_sessions.id"), unique=True, index=True
    )
    waveform: Mapped[list] = mapped_column(JSON)
    spectrogram_media_id: Mapped[UUID] = mapped_column(ForeignKey("media_files.id"))
    algorithm_version: Mapped[str] = mapped_column(String(40))
    generated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class AuditEvent(Base):
    __tablename__ = "audit_events"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    actor_account_id: Mapped[UUID | None] = mapped_column(ForeignKey("accounts.id"), index=True)
    action: Mapped[str] = mapped_column(String(80), index=True)
    object_type: Mapped[str] = mapped_column(String(40), index=True)
    object_id: Mapped[UUID | None] = mapped_column(PostgresUUID(as_uuid=True), index=True)
    detail: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC), index=True
    )
