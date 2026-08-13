FROM ghcr.io/astral-sh/uv:0.9.0-python3.13-bookworm-slim

WORKDIR /app/server

COPY server/pyproject.toml server/uv.lock ./
RUN uv sync --frozen --no-dev

COPY server/ ./

CMD ["uv", "run", "python", "manage.py", "runserver", "0.0.0.0:8000"]
