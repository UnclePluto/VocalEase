import json
import urllib.error
import urllib.request
from dataclasses import asdict
from uuid import UUID

from vocaease_worker.mixing import MixOutput
from vocaease_worker.separation import StoredOutput


class StaleTaskError(Exception):
    pass


class SeparationCallbackClient:
    def __init__(self, api_base_url: str, token: str) -> None:
        self.api_base_url = api_base_url.rstrip("/")
        self.token = token

    def _post(self, job_id: UUID, action: str, payload: dict[str, object]) -> None:
        request = urllib.request.Request(
            f"{self.api_base_url}/api/v1/internal/separations/{job_id}/{action}",
            data=json.dumps(payload).encode(),
            headers={
                "Content-Type": "application/json",
                "X-VocaEase-Worker-Token": self.token,
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status >= 300:
                    raise RuntimeError(f"内部回调返回 {response.status}")
        except urllib.error.HTTPError as error:
            if error.code == 409:
                raise StaleTaskError from error
            raise RuntimeError(f"内部回调返回 {error.code}") from error

    def started(self, job_id: UUID, attempt: int) -> None:
        self._post(job_id, "started", {"attempt": attempt})

    def completed(
        self,
        job_id: UUID,
        attempt: int,
        vocals: StoredOutput,
        no_vocals: StoredOutput,
    ) -> None:
        self._post(
            job_id,
            "completed",
            {
                "attempt": attempt,
                "vocals": asdict(vocals),
                "no_vocals": asdict(no_vocals),
            },
        )

    def failed(self, job_id: UUID, attempt: int, failure_code: str) -> None:
        self._post(
            job_id,
            "failed",
            {"attempt": attempt, "failure_code": failure_code},
        )


class PlaybackMixCallbackClient:
    def __init__(self, api_base_url: str, token: str) -> None:
        self.api_base_url = api_base_url.rstrip("/")
        self.token = token

    def _post(self, job_id: UUID, action: str, payload: dict[str, object]) -> None:
        request = urllib.request.Request(
            f"{self.api_base_url}/api/v1/internal/playback-mixes/{job_id}/{action}",
            data=json.dumps(payload).encode(),
            headers={
                "Content-Type": "application/json",
                "X-VocaEase-Worker-Token": self.token,
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status >= 300:
                    raise RuntimeError(f"内部回调返回 {response.status}")
        except urllib.error.HTTPError as error:
            if error.code == 409:
                raise StaleTaskError from error
            raise RuntimeError(f"内部回调返回 {error.code}") from error

    def started(self, job_id: UUID, attempt: int) -> None:
        self._post(job_id, "started", {"attempt": attempt})

    def completed(self, job_id: UUID, attempt: int, output: MixOutput) -> None:
        self._post(
            job_id,
            "completed",
            {"attempt": attempt, "output": asdict(output)},
        )

    def failed(self, job_id: UUID, attempt: int, failure_code: str) -> None:
        self._post(
            job_id,
            "failed",
            {"attempt": attempt, "failure_code": failure_code},
        )
