#!/bin/sh
set -eu

media_origins=${WEB_MEDIA_ORIGIN:-}

if printf '%s' "$media_origins" | LC_ALL=C grep -q '[[:cntrl:]]'; then
    echo "WEB_MEDIA_ORIGIN 只能包含以空格分隔的 HTTPS 来源" >&2
    exit 1
fi

for origin in $media_origins; do
    if ! printf '%s\n' "$origin" | LC_ALL=C grep -Eq '^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$'; then
        echo "WEB_MEDIA_ORIGIN 包含无效来源: $origin" >&2
        exit 1
    fi
done
