import pytest
from rest_framework.exceptions import ValidationError

from apps.songs.lyrics import parse_lrc


def test_lrc_bom_multiple_tags_offset_and_stable_order():
    content = '\ufeff[offset:-500]\n[ti:歌名]\n[00:01.00][00:02.000]第一句\n[00:01.00]第二句\n'.encode()
    assert parse_lrc(content) == [
        {"time_ms": 500, "text": "第一句"},
        {"time_ms": 500, "text": "第二句"},
        {"time_ms": 1500, "text": "第一句"},
    ]


@pytest.mark.parametrize("content", [b"", b"[ti:only metadata]", b"[00:60.00]wrong", b"[00:01.00]", b"plain lyrics", b"\xff"])
def test_lrc_rejects_invalid_or_empty_content(content):
    with pytest.raises(ValidationError):
        parse_lrc(content)
