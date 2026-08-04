import urllib.error
from uuid import uuid4

import pytest
from vocaease_worker.callback import (
    CallbackDeliveryError,
    SeparationCallbackClient,
    StaleTaskError,
)


def test_server_error_is_retryable_callback_delivery_failure(monkeypatch):
    def fail(*_args, **_kwargs):
        raise urllib.error.HTTPError(
            url="http://api.test",
            code=503,
            msg="unavailable",
            hdrs=None,
            fp=None,
        )

    monkeypatch.setattr("urllib.request.urlopen", fail)
    client = SeparationCallbackClient("http://api.test", "token")

    with pytest.raises(CallbackDeliveryError):
        client.started(uuid4(), 1)


def test_conflict_marks_callback_as_stale_instead_of_retrying(monkeypatch):
    def conflict(*_args, **_kwargs):
        raise urllib.error.HTTPError(
            url="http://api.test",
            code=409,
            msg="conflict",
            hdrs=None,
            fp=None,
        )

    monkeypatch.setattr("urllib.request.urlopen", conflict)
    client = SeparationCallbackClient("http://api.test", "token")

    with pytest.raises(StaleTaskError):
        client.started(uuid4(), 1)
