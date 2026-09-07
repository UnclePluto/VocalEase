#!/usr/bin/env python3
"""部署前验证；不打印环境变量或凭据，兼容服务器 Python 3.6。"""

import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

REGISTRY = "crpi-vu9eu0iguupfgpzi.cn-guangzhou.personal.cr.aliyuncs.com/dypluto/voca-ease"


def read_env(path):
    result = {}
    for raw in Path(path).read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError("环境文件格式不合法")
        if value[:1] in ("'", '"') and value[-1:] == value[:1]:
            value = value[1:-1]
        result[key] = value
    return result


def validate(environment, images):
    required = ("DJANGO_SECRET_KEY", "POSTGRES_PASSWORD", "QINIU_ACCESS_KEY", "QINIU_SECRET_KEY", "QINIU_BUCKET", "QINIU_DOMAIN", "QINIU_CALLBACK_URL", "WEB_MEDIA_ORIGIN")
    for key in required:
        if not environment.get(key) or environment[key] == "change-me":
            raise ValueError("缺少正式配置：" + key)
    for key in ("DJANGO_SECRET_KEY", "POSTGRES_PASSWORD"):
        if len(environment[key].encode()) < 32:
            raise ValueError(key + " 长度不足")
    fixed = {
        "DJANGO_SETTINGS_MODULE": "vocaease.settings.base",
        "DJANGO_DEBUG": "false",
        "DJANGO_SECURE_SSL_REDIRECT": "true",
        "MEDIA_BACKEND": "qiniu",
        "MEDIA_ENVIRONMENT": "vocaease-production",
        "POSTGRES_DB": "vocaease",
        "POSTGRES_USER": "vocaease",
        "POSTGRES_HOST": "postgres",
        "REDIS_URL": "redis://redis:6379/0",
        "CELERY_BROKER_URL": "redis://redis:6379/0",
        "CELERY_RESULT_BACKEND": "redis://redis:6379/0",
        "AUTH_WEB_ALLOWED_ORIGINS": "https://vocaease.whestsun.com",
        "QINIU_CALLBACK_URL": "https://vocaease-api.whestsun.com/api/v1/media/qiniu/callback/",
    }
    for key, expected in fixed.items():
        if environment.get(key) != expected:
            raise ValueError("生产隔离配置不符合要求：" + key)
    for key in ("QINIU_DOMAIN", "QINIU_UPLOAD_URL"):
        url = urlsplit(environment.get(key, ""))
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError("需要完整 HTTPS 来源：" + key)
        origin = "https://" + url.netloc
        if origin not in environment["WEB_MEDIA_ORIGIN"].split():
            raise ValueError("WEB_MEDIA_ORIGIN 未允许 " + key)
    for host in ("vocaease.whestsun.com", "vocaease-api.whestsun.com", "localhost"):
        if host not in environment.get("DJANGO_ALLOWED_HOSTS", "").split(","):
            raise ValueError("DJANGO_ALLOWED_HOSTS 缺少所需域名")
    if set(images) != {"WEB_IMAGE", "SERVER_IMAGE", "POSTGRES_IMAGE", "REDIS_IMAGE"}:
        raise ValueError("发布清单必须包含四种镜像")
    for ref in images.values():
        if not re.fullmatch(re.escape(REGISTRY) + r"@sha256:[a-f0-9]{64}", ref):
            raise ValueError("镜像必须来自指定 ACR 仓库且固定摘要")


if __name__ == "__main__":
    try:
        env_path, release_path = map(Path, sys.argv[1:])
        if env_path.stat().st_mode & 0o077:
            raise ValueError("生产环境文件权限必须为 600")
        images = json.loads((release_path / "images.json").read_text())
        validate(read_env(env_path), images)
        target = release_path / "images.env"
        target.write_text("".join(key + "=" + value + "\n" for key, value in sorted(images.items())))
        os.chmod(str(target), 0o600)
        print("生产配置及镜像摘要验证通过")
    except (ValueError, OSError, TypeError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
