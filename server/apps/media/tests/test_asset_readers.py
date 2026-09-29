import hashlib
from base64 import urlsafe_b64encode
from io import BytesIO
from unittest.mock import Mock

import pytest
from rest_framework.exceptions import ValidationError

from apps.media.readers import _qiniu_etag, _read_limited


def test_qiniu_etag_matches_single_block_format():
    content = b"[00:01.00]hello"
    expected = urlsafe_b64encode(b"\x16" + hashlib.sha1(content).digest()).decode().rstrip("=")
    assert _qiniu_etag(content) == expected


def test_limited_reader_rejects_oversized_stream():
    with pytest.raises(ValidationError):
        _read_limited(BytesIO(b"abcdef"), max_bytes=5)


def test_limited_reader_accepts_exact_limit():
    assert _read_limited(BytesIO(b"abcde"), max_bytes=5) == b"abcde"
