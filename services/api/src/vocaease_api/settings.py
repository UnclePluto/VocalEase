from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="VOCAEASE_", extra="ignore")

    database_url: str = "postgresql+psycopg://vocaease:vocaease_dev@127.0.0.1:54329/vocaease"
    redis_url: str = "redis://127.0.0.1:63799/0"
    media_directory: Path = Path(".runtime/media")
    migration_config: Path = Path("services/api/alembic.ini")
    bootstrap_admin_username: str = "admin"
    bootstrap_admin_password: str = "admin888888"
    session_hours: int = 12
    max_audio_upload_bytes: int = 100 * 1024 * 1024
    separation_redis_url: str = "redis://127.0.0.1:63799/1"
    separation_model_name: str = "UVR-MDX-NET-Inst_HQ_3.onnx"
    worker_internal_token: str = "development-worker-token"
    playback_mix_algorithm_version: str = "ffmpeg-amix-v1"
    playback_media_signing_secret: str = "development-playback-signing-secret"
    playback_media_grant_seconds: int = 300
    quality_silence_amplitude: int = 32
    quality_clipping_amplitude: int = 32_734
    quality_silent_ratio_warning: float = 0.8
    quality_clipping_ratio_warning: float = 0.01
    quality_low_volume_dbfs: float = -42.0
    quality_window_ms: int = 500
