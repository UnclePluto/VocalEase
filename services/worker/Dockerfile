FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY services/worker/pyproject.toml services/worker/pyproject.toml
COPY services/worker/src services/worker/src
RUN pip install --no-cache-dir ./services/worker

CMD ["vocaease-worker"]
