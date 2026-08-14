#!/bin/sh
set -eu

media_root="${MEDIA_LOCAL_ROOT:-/app/private-media}"
mkdir -p "$media_root"

exec "$@"
