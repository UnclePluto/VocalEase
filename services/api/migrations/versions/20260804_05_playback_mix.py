"""建立回放混音任务及来源关联。"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260804_05"
down_revision: str | None = "20260804_04"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "playback_mix_jobs",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("singing_session_id", sa.UUID(), nullable=False),
        sa.Column("raw_voice_media_id", sa.UUID(), nullable=False),
        sa.Column("backing_track_id", sa.UUID(), nullable=False),
        sa.Column("accompaniment_start_frame", sa.BigInteger(), nullable=False),
        sa.Column("algorithm_version", sa.String(60), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("output_media_id", sa.UUID(), nullable=True),
        sa.Column("failure_code", sa.String(40), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["backing_track_id"], ["backing_track_versions.id"]),
        sa.ForeignKeyConstraint(["output_media_id"], ["media_files.id"]),
        sa.ForeignKeyConstraint(["raw_voice_media_id"], ["media_files.id"]),
        sa.ForeignKeyConstraint(["singing_session_id"], ["singing_sessions.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("singing_session_id"),
    )
    op.create_index(
        "ix_playback_mix_jobs_singing_session_id",
        "playback_mix_jobs",
        ["singing_session_id"],
    )
    op.create_index("ix_playback_mix_jobs_status", "playback_mix_jobs", ["status"])


def downgrade() -> None:
    op.drop_index("ix_playback_mix_jobs_status", table_name="playback_mix_jobs")
    op.drop_index(
        "ix_playback_mix_jobs_singing_session_id",
        table_name="playback_mix_jobs",
    )
    op.drop_table("playback_mix_jobs")
