from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="VOCAEASE_", extra="ignore")

    database_url: str = "postgresql+psycopg://vocaease:vocaease_dev@127.0.0.1:54329/vocaease"
    redis_url: str = "redis://127.0.0.1:63799/0"
    media_directory: Path = Path(".runtime/media")
    bootstrap_admin_username: str = "admin"
    bootstrap_admin_password: str = "admin888888"
    session_hours: int = 12
