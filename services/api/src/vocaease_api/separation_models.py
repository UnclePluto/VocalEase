from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import DateTime, ForeignKey, Integer, String
from sqlalchemy.dialects.postgresql import UUID as PostgresUUID
from sqlalchemy.orm import Mapped, mapped_column

from vocaease_api.database import Base


class SeparationJob(Base):
    __tablename__ = "separation_jobs"

    id: Mapped[UUID] = mapped_column(PostgresUUID(as_uuid=True), primary_key=True, default=uuid4)
    song_id: Mapped[UUID] = mapped_column(ForeignKey("songs.id"), index=True)
    source_media_id: Mapped[UUID] = mapped_column(ForeignKey("media_files.id"))
    status: Mapped[str] = mapped_column(String(20), index=True)
    attempts: Mapped[int] = mapped_column(Integer, default=1)
    model_name: Mapped[str] = mapped_column(String(160))
    vocals_media_id: Mapped[UUID | None] = mapped_column(ForeignKey("media_files.id"))
    no_vocals_media_id: Mapped[UUID | None] = mapped_column(ForeignKey("media_files.id"))
    approved_backing_track_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("backing_track_versions.id")
    )
    failure_code: Mapped[str | None] = mapped_column(String(40))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
    )
