"""建立声音实验室派生数据并支持参与者软删除。"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260804_06"
down_revision: str | None = "20260804_05"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "participants",
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_table(
        "session_media_analyses",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("singing_session_id", sa.UUID(), nullable=False),
        sa.Column("waveform", sa.JSON(), nullable=False),
        sa.Column("spectrogram_media_id", sa.UUID(), nullable=False),
        sa.Column("algorithm_version", sa.String(40), nullable=False),
        sa.Column("generated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["singing_session_id"], ["singing_sessions.id"]),
        sa.ForeignKeyConstraint(["spectrogram_media_id"], ["media_files.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("singing_session_id"),
    )
    op.create_index(
        "ix_session_media_analyses_singing_session_id",
        "session_media_analyses",
        ["singing_session_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_session_media_analyses_singing_session_id",
        table_name="session_media_analyses",
    )
    op.drop_table("session_media_analyses")
    op.drop_column("participants", "deleted_at")
