from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, String
from sqlalchemy.dialects.postgresql import UUID as PostgresUUID
from sqlalchemy.orm import Mapped, mapped_column

from vocaease_api.database import Base


class PlaybackMixJob(Base):
    __tablename__ = "playback_mix_jobs"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    singing_session_id: Mapped[UUID] = mapped_column(
        ForeignKey("singing_sessions.id"), unique=True, index=True
    )
    raw_voice_media_id: Mapped[UUID] = mapped_column(ForeignKey("media_files.id"))
    backing_track_id: Mapped[UUID] = mapped_column(ForeignKey("backing_track_versions.id"))
    accompaniment_start_frame: Mapped[int] = mapped_column(BigInteger)
    algorithm_version: Mapped[str] = mapped_column(String(60))
    status: Mapped[str] = mapped_column(String(20), index=True)
    attempts: Mapped[int] = mapped_column(Integer, default=1)
    output_media_id: Mapped[UUID | None] = mapped_column(ForeignKey("media_files.id"))
    failure_code: Mapped[str | None] = mapped_column(String(40))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
    )
