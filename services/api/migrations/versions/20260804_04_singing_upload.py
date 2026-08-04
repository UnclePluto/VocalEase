"""建立演唱会话、原始人声上传、技术质检和审计表。"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260804_04"
down_revision: str | None = "20260804_03"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "singing_sessions",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("participant_id", sa.UUID(), nullable=False),
        sa.Column("song_id", sa.UUID(), nullable=False),
        sa.Column("backing_track_id", sa.UUID(), nullable=False),
        sa.Column("lyric_version_id", sa.UUID(), nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("used_headphones", sa.Boolean(), nullable=False),
        sa.Column("headphone_risk_confirmed", sa.Boolean(), nullable=False),
        sa.Column("device_snapshot", sa.JSON(), nullable=False),
        sa.Column("pre_duration_ms", sa.Integer(), nullable=False),
        sa.Column("song_duration_ms", sa.Integer(), nullable=False),
        sa.Column("post_duration_ms", sa.Integer(), nullable=False),
        sa.Column("accompaniment_start_frame", sa.BigInteger(), nullable=True),
        sa.Column("interruption_reason", sa.String(60), nullable=True),
        sa.Column("raw_voice_media_id", sa.UUID(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["backing_track_id"], ["backing_track_versions.id"]),
        sa.ForeignKeyConstraint(["lyric_version_id"], ["lyric_versions.id"]),
        sa.ForeignKeyConstraint(["participant_id"], ["participants.id"]),
        sa.ForeignKeyConstraint(["raw_voice_media_id"], ["media_files.id"]),
        sa.ForeignKeyConstraint(["song_id"], ["songs.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_singing_sessions_participant_id", "singing_sessions", ["participant_id"])
    op.create_index("ix_singing_sessions_status", "singing_sessions", ["status"])
    op.create_table(
        "voice_uploads",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("singing_session_id", sa.UUID(), nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("expected_chunks", sa.Integer(), nullable=False),
        sa.Column("total_bytes", sa.BigInteger(), nullable=False),
        sa.Column("total_sha256", sa.String(64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("verified_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(
            ["singing_session_id"], ["singing_sessions.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("singing_session_id"),
    )
    op.create_index("ix_voice_uploads_singing_session_id", "voice_uploads", ["singing_session_id"])
    op.create_table(
        "voice_upload_chunks",
        sa.Column("upload_id", sa.UUID(), nullable=False),
        sa.Column("chunk_number", sa.Integer(), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("storage_key", sa.String(255), nullable=False),
        sa.ForeignKeyConstraint(["upload_id"], ["voice_uploads.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("upload_id", "chunk_number"),
        sa.UniqueConstraint("storage_key"),
    )
    op.create_table(
        "audio_quality_reports",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("singing_session_id", sa.UUID(), nullable=False),
        sa.Column("source", sa.String(20), nullable=False),
        sa.Column("algorithm_version", sa.String(40), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("metrics", sa.JSON(), nullable=False),
        sa.Column("generated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["singing_session_id"], ["singing_sessions.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_audio_quality_reports_singing_session_id",
        "audio_quality_reports",
        ["singing_session_id"],
    )
    op.create_table(
        "audit_events",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("actor_account_id", sa.UUID(), nullable=True),
        sa.Column("action", sa.String(80), nullable=False),
        sa.Column("object_type", sa.String(40), nullable=False),
        sa.Column("object_id", sa.UUID(), nullable=True),
        sa.Column("detail", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["actor_account_id"], ["accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_audit_events_action", "audit_events", ["action"])
    op.create_index("ix_audit_events_actor_account_id", "audit_events", ["actor_account_id"])
    op.create_index("ix_audit_events_created_at", "audit_events", ["created_at"])
    op.create_index("ix_audit_events_object_id", "audit_events", ["object_id"])
    op.create_index("ix_audit_events_object_type", "audit_events", ["object_type"])


def downgrade() -> None:
    op.drop_index("ix_audit_events_object_type", table_name="audit_events")
    op.drop_index("ix_audit_events_object_id", table_name="audit_events")
    op.drop_index("ix_audit_events_created_at", table_name="audit_events")
    op.drop_index("ix_audit_events_actor_account_id", table_name="audit_events")
    op.drop_index("ix_audit_events_action", table_name="audit_events")
    op.drop_table("audit_events")
    op.drop_index(
        "ix_audio_quality_reports_singing_session_id",
        table_name="audio_quality_reports",
    )
    op.drop_table("audio_quality_reports")
    op.drop_table("voice_upload_chunks")
    op.drop_index("ix_voice_uploads_singing_session_id", table_name="voice_uploads")
    op.drop_table("voice_uploads")
    op.drop_index("ix_singing_sessions_status", table_name="singing_sessions")
    op.drop_index("ix_singing_sessions_participant_id", table_name="singing_sessions")
    op.drop_table("singing_sessions")
