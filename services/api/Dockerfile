FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY services/api/pyproject.toml services/api/pyproject.toml
COPY services/api/src services/api/src
RUN pip install --no-cache-dir ./services/api

EXPOSE 8000

CMD ["uvicorn", "vocaease_api.app:app", "--host", "0.0.0.0", "--port", "8000"]
