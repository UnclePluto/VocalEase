from fastapi import FastAPI, Response, status

from vocaease_api.health import HealthReport, HealthService
from vocaease_api.settings import Settings


def create_app() -> FastAPI:
    app = FastAPI(title="VocaEase API", version="0.1.0")

    @app.get("/api/v1/health", response_model=HealthReport)
    def health(response: Response) -> HealthReport:
        report = HealthService(Settings()).report()
        if report.status != "healthy":
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return report

    return app


app = create_app()
