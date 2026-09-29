"""严格解析 UTF-8 LRC，统一转换为毫秒时间线。"""

import re

from rest_framework.exceptions import ValidationError


TIME = re.compile(r"\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]")
META = re.compile(r"\[(ti|ar|al|by|re|ve|length):[^\]]*\]$", re.IGNORECASE)
OFFSET = re.compile(r"\[offset:([+-]?\d+)\]$", re.IGNORECASE)


def parse_lrc(content: bytes) -> list[dict]:
    if not content or len(content) > 1024 * 1024:
        raise ValidationError({"lyrics": "歌词为空或超过 1MB"})
    try:
        decoded = content.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise ValidationError({"lyrics": "歌词必须使用 UTF-8 编码"}) from exc
    raw_lines = decoded.splitlines()
    offset = 0
    for number, line in enumerate(raw_lines, 1):
        match = OFFSET.fullmatch(line.strip())
        if match:
            offset = int(match.group(1))
    result = []
    for number, line in enumerate(raw_lines, 1):
        stripped = line.strip()
        if not stripped or META.fullmatch(stripped) or OFFSET.fullmatch(stripped):
            continue
        position = 0
        stamps = []
        while (match := TIME.match(stripped, position)) is not None:
            minute, second, fraction = match.groups()
            if int(second) >= 60:
                raise ValidationError({"lyrics": f"第 {number} 行秒数无效"})
            millis = (int(minute) * 60 + int(second)) * 1000 + int((fraction or "0").ljust(3, "0"))
            stamps.append(max(0, millis + offset))
            position = match.end()
        if not stamps:
            raise ValidationError({"lyrics": f"第 {number} 行缺少有效时间标签"})
        body = stripped[position:]
        if "[" in body or "]" in body:
            raise ValidationError({"lyrics": f"第 {number} 行包含无效标签"})
        for stamp in stamps:
            result.append({"time_ms": stamp, "text": body})
    if not any(item["text"] for item in result):
        raise ValidationError({"lyrics": "歌词至少需要一条带时间标签的正文"})
    return sorted(result, key=lambda item: item["time_ms"])
