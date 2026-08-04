"""建立两轨分离任务及其结果关联。"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260804_03"
down_revision: str | None = "20260804_02"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "separation_jobs",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("song_id", sa.UUID(), nullable=False),
        sa.Column("source_media_id", sa.UUID(), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("model_name", sa.String(160), nullable=False),
        sa.Column("vocals_media_id", sa.UUID(), nullable=True),
        sa.Column("no_vocals_media_id", sa.UUID(), nullable=True),
        sa.Column("approved_backing_track_id", sa.UUID(), nullable=True),
        sa.Column("failure_code", sa.String(40), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["approved_backing_track_id"], ["backing_track_versions.id"]),
        sa.ForeignKeyConstraint(["no_vocals_media_id"], ["media_files.id"]),
        sa.ForeignKeyConstraint(["song_id"], ["songs.id"]),
        sa.ForeignKeyConstraint(["source_media_id"], ["media_files.id"]),
        sa.ForeignKeyConstraint(["vocals_media_id"], ["media_files.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_separation_jobs_song_id", "separation_jobs", ["song_id"])
    op.create_index("ix_separation_jobs_status", "separation_jobs", ["status"])


def downgrade() -> None:
    op.drop_index("ix_separation_jobs_status", table_name="separation_jobs")
    op.drop_index("ix_separation_jobs_song_id", table_name="separation_jobs")
    op.drop_table("separation_jobs")
