#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
export PATH=/usr/local/bin:/usr/bin:/bin
BASE=/home/motioncare/vocaease-production
RELEASE=$(realpath "${1:?需要发布目录}")
[[ "$RELEASE" =~ ^$BASE/releases/[a-f0-9]{40}$ ]] || { echo '发布目录不合法' >&2; exit 1; }
exec 9>"$BASE/deploy.lock"
flock -n 9 || { echo '已有 VocaEase 发布正在执行' >&2; exit 1; }
export APP_ENV_FILE="$BASE/.env"
python3 "$RELEASE/validate-production.py" "$APP_ENV_FILE" "$RELEASE"
compose() { docker compose -p vocaease-prod --env-file "$APP_ENV_FILE" --env-file "$RELEASE/images.env" -f "$RELEASE/compose.prod.yaml" "$@"; }
compose config --quiet
docker network inspect whest_Lan >/dev/null
docker exec OpenResty nginx -t
test -f /opt/service/openresty_ssl_conf.d/vocaease-probe.conf
test -x /home/motioncare/vocaease-https/install-config.sh
free_kb=$(df -Pk "$BASE" | awk 'NR==2 {print $4}')
[[ "$free_kb" -ge 6291456 ]] || { echo '磁盘可用不足 6GiB，停止发布' >&2; exit 1; }
available_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
[[ "$available_kb" -ge 3145728 ]] || { echo '可用内存不足 3GiB，停止发布' >&2; exit 1; }
# 已发布时端口由本项目占用；首次发布必须验证能绑定，不能抢占其他进程。
if [[ -z "$(compose ps -q web)" ]]; then
    python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",19080)); s.close()'
fi
mkdir -p "$BASE/backups" "$BASE/logs"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BASE/backups/$stamp"
mkdir "$backup"
cp "$APP_ENV_FILE" "$backup/production.env"
cp /opt/service/openresty_ssl_conf.d/vocaease-probe.conf "$backup/entry.conf"
previous=$(readlink -f "$BASE/current" || true)
printf '%s\n' "$previous" > "$backup/previous-release"
docker ps --format '{{.ID}} {{.Names}} {{.Status}}' > "$backup/containers.before"
compose pull --quiet
# 始终只操作 vocaease-prod，禁止全局清理、操作共享网络或其他项目容器。
compose up -d --wait --wait-timeout 120 postgres redis
compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup/database.dump.partial"
mv "$backup/database.dump.partial" "$backup/database.dump"
# 数据迁移失败时保留备份及现场；不自动降级数据库或盲目运行旧应用。
compose stop web server celery celery-beat
compose run --rm --no-deps server uv run --no-sync python manage.py check
compose run --rm --no-deps server uv run --no-sync python manage.py migrate --noinput
if ! compose up -d --wait --wait-timeout 180 server celery celery-beat web; then
    echo "应用启动失败；备份位于 $backup。数据库已可能迁移，需检查兼容性后回滚。" >&2
    exit 1
fi
curl --noproxy '*' -fsS --max-time 15 -H 'Host: vocaease.whestsun.com' http://127.0.0.1:19080/ >/dev/null
compose exec -T server /app/server/.venv/bin/python -c "from urllib.request import Request,urlopen; import json; r=urlopen(Request('http://localhost:8000/health/ready/',headers={'X-Forwarded-Proto':'https'})); assert json.load(r)['code']=='ok'"
/home/motioncare/vocaease-https/install-config.sh "$RELEASE/openresty.vocaease.conf"
if ! docker exec OpenResty nginx -t; then
    /home/motioncare/vocaease-https/install-config.sh "$backup/entry.conf"
    exit 1
fi
docker exec OpenResty nginx -s reload
# 平滑加载异步完成；有限重试避免将旧 worker 的瞬间响应视为失败。
for host in vocaease.whestsun.com vocaease-api.whestsun.com; do
    success=false
    for attempt in 1 2 3 4 5; do
        if curl --noproxy '*' -fsS --max-time 15 --resolve "$host:443:127.0.0.1" "https://$host/api/schema/" | python3 -c 'import sys; s=sys.stdin.read(); assert "openapi:" in s or "\"openapi\"" in s'; then success=true; break; fi
        sleep 2
    done
    if [[ "$success" != true ]]; then
        /home/motioncare/vocaease-https/install-config.sh "$backup/entry.conf"
        docker exec OpenResty nginx -t && docker exec OpenResty nginx -s reload
        echo '入口验收失败，已恢复原入口配置；请检查本项目容器。' >&2
        exit 1
    fi
done
ln -s "$RELEASE" "$BASE/.current-next"
mv -Tf "$BASE/.current-next" "$BASE/current"
printf '%s\n' "$stamp" > "$RELEASE/deployed-at"
docker ps --format '{{.ID}} {{.Names}} {{.Status}}' > "$backup/containers.after"
echo "VocaEase 发布成功：$(basename "$RELEASE")"
