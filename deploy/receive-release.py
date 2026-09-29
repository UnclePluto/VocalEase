#!/usr/bin/env python3
"""专用 SSH 发布密钥的强制命令：仅接收有限大小的指定部署文件。"""
import io
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile

BASE = Path("/home/motioncare/vocaease-production")
FILES = {"compose.prod.yaml", "validate-production.py", "deploy-production.sh", "openresty.vocaease.conf", "images.json"}
match = re.fullmatch(r"deploy ([a-f0-9]{40})", os.environ.get("SSH_ORIGINAL_COMMAND", ""))
if not match:
    raise SystemExit("只允许 deploy <40位提交SHA>")
payload = sys.stdin.buffer.read(1024 * 1024 + 1)
if len(payload) > 1024 * 1024:
    raise SystemExit("发布包超过限制")
with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
    members = archive.getmembers()
    if len(members) != len(FILES) or {m.name for m in members} != FILES or any(not m.isfile() or m.size > 256 * 1024 for m in members):
        raise SystemExit("发布包文件不合法")
    release = BASE / "releases" / match.group(1)
    release.mkdir(mode=0o700, parents=True, exist_ok=True)
    if (release / "deployed-at").exists():
        raise SystemExit("该版本已发布，请使用新提交或人工回滚流程")
    for member in members:
        target = release / member.name
        if target.is_symlink():
            raise SystemExit("不允许符号链接")
        target.write_bytes(archive.extractfile(member).read())
        target.chmod(0o600)
subprocess.run(["bash", str(release / "deploy-production.sh"), str(release)], check=True)
