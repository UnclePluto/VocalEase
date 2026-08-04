from contextlib import asynccontextmanager

from fastapi import FastAPI, Response, status

from vocaease_api.database import initialize_database
from vocaease_api.health import HealthReport, HealthService
from vocaease_api.identity import bootstrap_admin
from vocaease_api.identity_routes import router as identity_router
from vocaease_api.settings import Settings


def create_app() -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        settings = Settings()
        app.state.session_factory = initialize_database(settings)
        with app.state.session_factory() as session:
            bootstrap_admin(session, settings)
        yield

    app = FastAPI(title="VocaEase API", version="0.1.0", lifespan=lifespan)
    app.include_router(identity_router)

    @app.get("/api/v1/health", response_model=HealthReport)
    def health(response: Response) -> HealthReport:
        report = HealthService(Settings()).report()
        if report.status != "healthy":
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return report

    return app


app = create_app()
