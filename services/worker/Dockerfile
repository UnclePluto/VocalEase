FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends -y ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY services/worker/pyproject.toml services/worker/pyproject.toml
COPY services/worker/src services/worker/src
RUN pip install --no-cache-dir \
    torch==2.12.1 \
    torchvision==0.27.1 \
    --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir ./services/worker "audio-separator[cpu]==0.44.5"

CMD ["vocaease-worker"]
