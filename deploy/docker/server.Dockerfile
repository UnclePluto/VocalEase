FROM ghcr.io/astral-sh/uv:0.9.0-python3.13-bookworm-slim

RUN useradd --create-home --uid 10001 app

WORKDIR /app/server

COPY server/pyproject.toml server/uv.lock ./
RUN uv sync --frozen --no-dev

COPY server/ ./
COPY deploy/docker/server-entrypoint.sh /usr/local/bin/vocaease-entrypoint

RUN mkdir -p /app/private-media \
    && chown -R app:app /app/server /app/private-media \
    && chmod 0755 /usr/local/bin/vocaease-entrypoint

USER app

ENTRYPOINT ["/usr/local/bin/vocaease-entrypoint"]

CMD ["uv", "run", "--no-sync", "python", "manage.py", "runserver", "0.0.0.0:8000"]
