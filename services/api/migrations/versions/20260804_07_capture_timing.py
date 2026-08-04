"""记录连续采集的单调时钟与音频帧位置。"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260804_07"
down_revision: str | None = "20260804_06"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "singing_sessions",
        sa.Column("audio_start_monotonic_ns", sa.BigInteger(), nullable=True),
    )
    op.add_column(
        "singing_sessions",
        sa.Column("accompaniment_start_monotonic_ns", sa.BigInteger(), nullable=True),
    )
    op.add_column(
        "singing_sessions",
        sa.Column("recorded_frame_count", sa.BigInteger(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("singing_sessions", "recorded_frame_count")
    op.drop_column("singing_sessions", "accompaniment_start_monotonic_ns")
    op.drop_column("singing_sessions", "audio_start_monotonic_ns")
