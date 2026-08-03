from pathlib import Path

from pydantic import BaseModel
from redis import Redis
from sqlalchemy import create_engine, text

from vocaease_api.settings import Settings


class DependencyStatus(BaseModel):
    database: str
    redis: str
    media_storage: str


class HealthReport(BaseModel):
    status: str
    dependencies: DependencyStatus


class HealthService:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def report(self) -> HealthReport:
        dependencies = DependencyStatus(
            database=self._database_status(),
            redis=self._redis_status(),
            media_storage=self._media_storage_status(),
        )
        values = (
            dependencies.database,
            dependencies.redis,
            dependencies.media_storage,
        )
        return HealthReport(
            status="healthy" if all(value == "up" for value in values) else "degraded",
            dependencies=dependencies,
        )

    def _database_status(self) -> str:
        engine = create_engine(self._settings.database_url, pool_pre_ping=True)
        try:
            with engine.connect() as connection:
                connection.execute(text("SELECT 1"))
            return "up"
        except Exception:
            return "down"
        finally:
            engine.dispose()

    def _redis_status(self) -> str:
        client = Redis.from_url(self._settings.redis_url)
        try:
            return "up" if client.ping() else "down"
        except Exception:
            return "down"
        finally:
            client.close()

    def _media_storage_status(self) -> str:
        directory = Path(self._settings.media_directory)
        probe = directory / ".health-probe"
        try:
            directory.mkdir(parents=True, exist_ok=True)
            probe.write_bytes(b"ok")
            probe.unlink()
            return "up"
        except OSError:
            return "down"
