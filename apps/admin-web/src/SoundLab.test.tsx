import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";

import { SoundLab } from "./SoundLab";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it("switches real lab data, renders markers, authenticates media and confirms recording deletion", async () => {
  const requests: Array<{ path: string; init?: RequestInit }> = [];
  let deleted = false;
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    requests.push({ path, init });
    if (path === "/api/v1/admin/singing-sessions/summary") {
      return jsonResponse(deleted ? [summary("session-2", "第二首歌")] : [
        summary("session-1", "第一首歌"),
        summary("session-2", "第二首歌")
      ]);
    }
    if (path === "/api/v1/admin/singing-sessions/session-1/lab") {
      return jsonResponse(lab("session-1", "第一首歌"));
    }
    if (path === "/api/v1/admin/singing-sessions/session-2/lab") {
      return jsonResponse(lab("session-2", "第二首歌"));
    }
    if (
      path === "/api/v1/admin/singing-sessions/session-1/raw-voice" ||
      path === "/api/v1/admin/singing-sessions/session-2/raw-voice" ||
      path.endsWith("download=true") ||
      path.startsWith("/api/v1/media/spectrogram-")
    ) {
      return blobResponse();
    }
    if (path === "/api/v1/singing-sessions/session-1/playback-mix") {
      return jsonResponse({
        id: "mix-1",
        singing_session_id: "session-1",
        status: "succeeded",
        attempts: 1,
        algorithm_version: "mix-v1",
        accompaniment_start_frame: 144000,
        failure_code: null,
        failure_message: null,
        experience_file: true,
        media_ready: true
      });
    }
    if (path === "/api/v1/singing-sessions/session-1/playback-mix/access") {
      return jsonResponse({
        url: "/api/v1/playback-mixes/mix-1/media?token=short-lived-token",
        expires_in_seconds: 120,
        experience_file: true
      });
    }
    if (path === "/api/v1/admin/singing-sessions/session-1" && init?.method === "DELETE") {
      deleted = true;
      return noContentResponse();
    }
    throw new Error(`未处理的请求：${init?.method ?? "GET"} ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  class MockURL extends URL {
    static createObjectURL = vi.fn(() => "blob:protected-media");
    static revokeObjectURL = vi.fn();
  }
  vi.stubGlobal("URL", MockURL);
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);

  const { container } = render(<SoundLab token="admin-token" onError={vi.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: /第一首歌/ }));

  expect(await screen.findByRole("img", { name: /真实波形/ })).toBeInTheDocument();
  expect(container.querySelectorAll("[data-waveform-point]")).toHaveLength(3);
  expect(container.querySelectorAll("[data-stage-boundary]")).toHaveLength(2);
  expect(container.querySelector("[data-quality-marker='silence']")).not.toBeNull();
  expect(await screen.findByText("录音大部分为静音")).toBeInTheDocument();
  expect(screen.getByText(/不代表健康状况、疾病诊断或演唱水平/)).toBeInTheDocument();

  await waitFor(() => {
    expect(
      requests.some(
        ({ path, init }) =>
          path === "/api/v1/admin/singing-sessions/session-1/raw-voice" &&
          headerValue(init, "Authorization") === "Bearer admin-token"
      )
    ).toBe(true);
    expect(
      requests.some(
        ({ path, init }) =>
          path === "/api/v1/media/spectrogram-session-1" &&
          headerValue(init, "Authorization") === "Bearer admin-token"
      )
    ).toBe(true);
  });

  fireEvent.click(screen.getByRole("button", { name: /静音区间/ }));
  const rawAudio = await screen.findByLabelText("原始人声轨");
  expect((rawAudio as HTMLAudioElement).currentTime).toBe(0.5);

  fireEvent.click(screen.getByRole("button", { name: "下载原始人声 WAV" }));
  await waitFor(() =>
    expect(
      requests.some(
        ({ path, init }) =>
          path.endsWith("/raw-voice?download=true") &&
          headerValue(init, "Authorization") === "Bearer admin-token"
      )
    ).toBe(true)
  );

  fireEvent.click(screen.getByRole("button", { name: "获取体验回放" }));
  expect(await screen.findByLabelText("体验回放混音")).toHaveAttribute(
    "src",
    "/api/v1/playback-mixes/mix-1/media?token=short-lived-token"
  );
  expect(screen.getByText(/体验文件，只用于回听/)).toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: /第二首歌/ }));
  expect(await screen.findByRole("heading", { name: /第二首歌/ })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: /第一首歌/ }));
  await screen.findByRole("heading", { name: /第一首歌/ });

  fireEvent.click(screen.getByRole("button", { name: "删除本次录音" }));
  const dialog = await screen.findByRole("dialog", { name: "再次确认删除本次录音" });
  expect(withinText(dialog, "无法恢复")).toBe(true);
  fireEvent.click(screen.getByRole("button", { name: /永久删除录音/ }));
  await waitFor(() =>
    expect(
      requests.some(
        ({ path, init }) =>
          path === "/api/v1/admin/singing-sessions/session-1" && init?.method === "DELETE"
      )
    ).toBe(true)
  );
  expect(await screen.findByText("选择一条演唱会话")).toBeInTheDocument();
});

function summary(id: string, title: string) {
  return {
    id,
    participant_research_code: "R-001",
    song_title: title,
    status: "submitted",
    upload_status: "verified",
    quality_status: "warning",
    playback_mix_status: "succeeded",
    used_headphones: true,
    headphone_risk_confirmed: false
  };
}

function lab(id: string, title: string) {
  return {
    singing_session_id: id,
    participant_research_code: "R-001",
    song_title: title,
    song_artist: "测试歌手",
    status: "submitted",
    stages: {
      pre_start_ms: 0,
      singing_start_ms: 3000,
      singing_end_ms: 9000,
      post_end_ms: 12000
    },
    accompaniment_start_frame: 144000,
    device_snapshot: {
      manufacturer: "Google",
      model: "Pixel 7",
      android_version: "14",
      app_version: "0.1.0",
      input_type: "built_in_mic",
      output_route: "wired_headphones",
      bluetooth_mode: null,
      sample_rate: 48000,
      channels: 1,
      bit_depth: 16
    },
    quality_reports: [
      {
        source: "server",
        algorithm_version: "wav-qc-v1",
        status: "warning",
        metrics: {
          sample_rate: 48000,
          channels: 1,
          bit_depth: 16,
          duration_ms: 12000,
          rms_dbfs: -48.2,
          silent_sample_ratio: 0.82,
          clipped_sample_ratio: 0,
          file_warnings: ["录音大部分为静音"],
          markers: [
            { kind: "silence", start_ms: 500, end_ms: 1000, value: 0.99 }
          ]
        }
      }
    ],
    waveform: [
      { start_ms: 0, min: -0.2, max: 0.3, rms: 0.1 },
      { start_ms: 4000, min: -0.7, max: 0.6, rms: 0.3 },
      { start_ms: 8000, min: -0.4, max: 0.5, rms: 0.2 }
    ],
    spectrogram_url: `/api/v1/media/spectrogram-${id}`,
    raw_voice_url: `/api/v1/admin/singing-sessions/${id}/raw-voice`,
    playback_mix_status: "succeeded",
    playback_mix_experience_file: true
  };
}

function jsonResponse(payload: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => payload
  };
}

function blobResponse() {
  return {
    ok: true,
    status: 200,
    blob: async () => new Blob(["media"], { type: "audio/wav" })
  };
}

function noContentResponse() {
  return {
    ok: true,
    status: 204,
    json: async () => ({})
  };
}

function headerValue(init: RequestInit | undefined, key: string): string | null {
  return new Headers(init?.headers).get(key);
}

function withinText(element: HTMLElement, text: string): boolean {
  return element.textContent?.includes(text) ?? false;
}
