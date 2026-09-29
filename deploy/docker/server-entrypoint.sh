#!/bin/sh
set -eu

media_root="${MEDIA_LOCAL_ROOT:-/app/private-media}"
mkdir -p "$media_root"

if [ "${RUN_MIGRATIONS:-0}" = "1" ]; then
  uv run --no-sync python manage.py migrate --noinput
fi

exec "$@"
