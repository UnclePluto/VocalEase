import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";

import { AdminApp } from "./AdminApp";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("allows an administrator to login and create a participant", async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({ access_token: "admin-token", must_change_password: false })
    })
    .mockResolvedValueOnce({ ok: true, json: async () => [] })
    .mockResolvedValueOnce({ ok: true, json: async () => [] })
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        id: "participant-1",
        account_id: "account-1",
        name: "测试参与者",
        phone: "13900000000",
        research_code: "R-001",
        active: true,
        must_change_password: true
      })
    });
  vi.stubGlobal("fetch", fetchMock);

  render(<AdminApp />);
  fireEvent.change(screen.getByLabelText("管理员账号"), { target: { value: "admin" } });
  fireEvent.change(screen.getByLabelText("管理员密码"), {
    target: { value: "admin888888" }
  });
  fireEvent.click(screen.getByRole("button", { name: /登\s*录/ }));

  expect(await screen.findByText("研究管理后台")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("tab", { name: "参与者" }));
  fireEvent.change(screen.getByLabelText("姓名"), { target: { value: "测试参与者" } });
  fireEvent.change(screen.getByLabelText("手机号"), { target: { value: "13900000000" } });
  fireEvent.change(screen.getByLabelText("研究编号"), { target: { value: "R-001" } });
  fireEvent.click(screen.getByRole("button", { name: "创建参与者" }));

  expect(await screen.findByText("测试参与者")).toBeInTheDocument();
  expect(screen.getByText("初始密码待修改")).toBeInTheDocument();
});

it("creates a song, uploads a backing track, saves LRC and publishes a version", async () => {
  const requests: Array<{ path: string; method: string }> = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    const method = init?.method ?? "GET";
    requests.push({ path, method });

    if (path === "/api/v1/auth/admin/login") {
      return jsonResponse({ access_token: "admin-token" });
    }
    if (path === "/api/v1/admin/participants") return jsonResponse([]);
    if (path === "/api/v1/admin/songs" && method === "GET") return jsonResponse([]);
    if (path === "/api/v1/admin/songs" && method === "POST") {
      return jsonResponse({
        id: "song-1",
        title: "测试歌曲",
        artist: "测试歌手",
        cover_url: null
      });
    }
    if (path === "/api/v1/admin/separations?song_id=song-1") return jsonResponse([]);
    if (path === "/api/v1/admin/songs/song-1/backing-tracks") {
      return jsonResponse({
        id: "track-1",
        version: 1,
        duration_ms: 90000,
        sample_rate: 48000,
        channels: 2,
        source_sha256: "1234567890abcdef",
        audio_url: "/api/v1/media/audio-1",
        review_status: "approved"
      });
    }
    if (path === "/api/v1/media/audio-1") {
      return {
        ok: true,
        status: 200,
        blob: async () => new Blob(["audio"], { type: "audio/mp4" })
      };
    }
    if (path === "/api/v1/admin/backing-tracks/track-1/lyrics") {
      return jsonResponse({
        id: "lyrics-1",
        backing_track_id: "track-1",
        version: 1,
        lines: [{ time_ms: 1000, text: "第一句" }]
      });
    }
    if (path === "/api/v1/admin/songs/song-1/publish") return noContentResponse();
    throw new Error(`未处理的请求：${method} ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("URL", {
    ...URL,
    createObjectURL: vi.fn(() => "blob:preview"),
    revokeObjectURL: vi.fn()
  });

  render(<AdminApp />);
  fireEvent.change(screen.getByLabelText("管理员密码"), {
    target: { value: "admin888888" }
  });
  fireEvent.click(screen.getByRole("button", { name: /登\s*录/ }));

  await screen.findByText("研究曲库");
  fireEvent.change(screen.getByLabelText("歌曲名称"), { target: { value: "测试歌曲" } });
  fireEvent.change(screen.getByLabelText("歌手"), { target: { value: "测试歌手" } });
  fireEvent.click(screen.getByRole("button", { name: "创建歌曲" }));

  expect((await screen.findAllByText("测试歌曲")).length).toBeGreaterThan(0);
  const audioFile = new File(["audio"], "backing.wav", { type: "audio/wav" });
  fireEvent.change(screen.getByLabelText("伴奏文件"), { target: { files: [audioFile] } });
  fireEvent.click(screen.getByRole("button", { name: "上传并处理伴奏" }));

  expect(await screen.findByText("伴奏已通过")).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("LRC 歌词"), {
    target: { value: "[00:01.00]第一句\n[00:05.00]第二句" }
  });
  fireEvent.click(screen.getByRole("button", { name: "保存为新歌词版本" }));

  await waitFor(() =>
    expect(screen.getByRole("button", { name: "发布到研究曲库" })).toBeEnabled()
  );
  fireEvent.click(screen.getByRole("button", { name: "发布到研究曲库" }));
  expect(await screen.findByText("曲库中可见")).toBeInTheDocument();
  expect(requests).toContainEqual({
    path: "/api/v1/admin/songs/song-1/publish",
    method: "POST"
  });
});

it("searches and edits participants and filters redacted audit records", async () => {
  const requests: string[] = [];
  const requestBodies: unknown[] = [];
  let participantDeleted = false;
  const participant = {
    id: "participant-1",
    account_id: "account-1",
    name: "测试参与者",
    phone: "13900000000",
    research_code: "R-001",
    active: true,
    must_change_password: false
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    requests.push(path);
    requestBodies.push(init?.body);
    if (path === "/api/v1/auth/admin/login") {
      return jsonResponse({ access_token: "admin-token" });
    }
    if (path === "/api/v1/admin/participants") {
      return jsonResponse(participantDeleted ? [] : [participant]);
    }
    if (path === "/api/v1/admin/participants?q=R-001") return jsonResponse([participant]);
    if (path === "/api/v1/admin/songs") return jsonResponse([]);
    if (path === "/api/v1/admin/participants/participant-1" && init?.method === "PATCH") {
      return jsonResponse({ ...participant, name: "修改后姓名", research_code: "R-002" });
    }
    if (path === "/api/v1/admin/participants/participant-1" && init?.method === "DELETE") {
      participantDeleted = true;
      return noContentResponse();
    }
    if (path.startsWith("/api/v1/admin/audit-events")) {
      return jsonResponse([
        {
          id: "audit-1",
          actor_account_id: "11111111-1111-1111-1111-111111111111",
          action: "participant.updated",
          object_type: "participant",
          object_id: "participant-1",
          detail: {
            fields: ["name", "research_code"],
            password: "secret-value",
            media_content: "binary-value"
          },
          created_at: "2026-08-04T10:00:00Z"
        }
      ]);
    }
    throw new Error(`未处理的请求：${init?.method ?? "GET"} ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);

  render(<AdminApp />);
  fireEvent.change(screen.getByLabelText("管理员密码"), {
    target: { value: "admin888888" }
  });
  fireEvent.click(screen.getByRole("button", { name: /登\s*录/ }));

  await screen.findByText("研究管理后台");
  fireEvent.click(screen.getByRole("tab", { name: "参与者" }));
  const search = screen.getByPlaceholderText("按姓名、手机号或研究编号搜索");
  fireEvent.change(search, { target: { value: "R-001" } });
  fireEvent.click(screen.getByRole("button", { name: /搜\s*索/ }));
  await waitFor(() =>
    expect(requests).toContain("/api/v1/admin/participants?q=R-001")
  );

  fireEvent.click(screen.getByRole("button", { name: "编辑资料" }));
  const dialog = await screen.findByRole("dialog");
  fireEvent.change(within(dialog).getByLabelText("姓名"), {
    target: { value: "修改后姓名" }
  });
  fireEvent.change(within(dialog).getByLabelText("研究编号"), {
    target: { value: "R-002" }
  });
  fireEvent.click(within(dialog).getByRole("button", { name: /保存修改/ }));
  expect(await screen.findByText("修改后姓名")).toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: "删除参与者" }));
  const deleteDialog = await screen.findByRole("dialog", { name: "删除测试参与者" });
  expect(within(deleteDialog).getByRole("button", { name: /确认删除参与者/ })).toBeDisabled();
  fireEvent.click(
    within(deleteDialog).getByLabelText("同时永久删除该参与者的全部演唱数据")
  );
  fireEvent.click(within(deleteDialog).getByRole("button", { name: /确认删除参与者/ }));
  await waitFor(() =>
    expect(requests.filter((path) => path === "/api/v1/admin/participants/participant-1")).toHaveLength(2)
  );
  const deleteBody = requestBodies.find((body) => String(body).includes("delete_singing_data"));
  expect(JSON.parse(String(deleteBody))).toEqual({ delete_singing_data: true });

  fireEvent.click(screen.getByRole("tab", { name: "审计日志" }));
  expect(await screen.findByText("participant.updated")).toBeInTheDocument();
  expect(screen.getByText(/"fields":\["name","research_code"\]/)).toBeInTheDocument();
  expect(screen.queryByText("secret-value")).not.toBeInTheDocument();
  expect(screen.queryByText("binary-value")).not.toBeInTheDocument();

  fireEvent.change(screen.getByLabelText("操作者账号 ID"), {
    target: { value: "11111111-1111-1111-1111-111111111111" }
  });
  fireEvent.change(screen.getByLabelText("对象类型"), {
    target: { value: "participant" }
  });
  fireEvent.change(screen.getByLabelText("操作"), {
    target: { value: "participant.updated" }
  });
  fireEvent.click(screen.getByRole("button", { name: /筛选日志/ }));
  await waitFor(() =>
    expect(
      requests.some(
        (path) =>
          path.includes("actor_account_id=11111111-1111-1111-1111-111111111111") &&
          path.includes("object_type=participant") &&
          path.includes("action=participant.updated")
      )
    ).toBe(true)
  );
});

function jsonResponse(payload: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => payload
  };
}

function noContentResponse() {
  return {
    ok: true,
    status: 204,
    json: async () => ({})
  };
}
