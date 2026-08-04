"""建立歌曲、媒体、伴奏、歌词和发布版本表。"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260804_02"
down_revision: str | None = "20260804_01"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "media_files",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("storage_key", sa.String(255), nullable=False),
        sa.Column("content_type", sa.String(100), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("purpose", sa.String(40), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("storage_key"),
    )
    op.create_table(
        "songs",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("artist", sa.String(200), nullable=False),
        sa.Column("cover_media_id", sa.UUID(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["cover_media_id"], ["media_files.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_table(
        "backing_track_versions",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("song_id", sa.UUID(), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("source_media_id", sa.UUID(), nullable=False),
        sa.Column("normalized_media_id", sa.UUID(), nullable=False),
        sa.Column("duration_ms", sa.Integer(), nullable=False),
        sa.Column("sample_rate", sa.Integer(), nullable=False),
        sa.Column("channels", sa.Integer(), nullable=False),
        sa.Column("review_status", sa.String(20), nullable=False),
        sa.Column("source_kind", sa.String(30), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["normalized_media_id"], ["media_files.id"]),
        sa.ForeignKeyConstraint(["song_id"], ["songs.id"]),
        sa.ForeignKeyConstraint(["source_media_id"], ["media_files.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("song_id", "version", name="uq_backing_track_song_version"),
    )
    op.create_index("ix_backing_track_versions_song_id", "backing_track_versions", ["song_id"])
    op.create_table(
        "lyric_versions",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("backing_track_id", sa.UUID(), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("lrc_text", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["backing_track_id"], ["backing_track_versions.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("backing_track_id", "version", name="uq_lyric_track_version"),
    )
    op.create_index("ix_lyric_versions_backing_track_id", "lyric_versions", ["backing_track_id"])
    op.create_table(
        "song_publications",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("song_id", sa.UUID(), nullable=False),
        sa.Column("backing_track_id", sa.UUID(), nullable=False),
        sa.Column("lyric_version_id", sa.UUID(), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["backing_track_id"], ["backing_track_versions.id"]),
        sa.ForeignKeyConstraint(["lyric_version_id"], ["lyric_versions.id"]),
        sa.ForeignKeyConstraint(["song_id"], ["songs.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_song_publications_song_id", "song_publications", ["song_id"])


def downgrade() -> None:
    op.drop_index("ix_song_publications_song_id", table_name="song_publications")
    op.drop_table("song_publications")
    op.drop_index("ix_lyric_versions_backing_track_id", table_name="lyric_versions")
    op.drop_table("lyric_versions")
    op.drop_index("ix_backing_track_versions_song_id", table_name="backing_track_versions")
    op.drop_table("backing_track_versions")
    op.drop_table("songs")
    op.drop_table("media_files")
