import { afterEach, expect, it, vi } from "vitest";

import {
  acceptSeparation,
  createSeparation,
  getSeparation,
  listSeparations,
  rejectSeparation,
  retrySeparation
} from "./admin-api";

afterEach(() => vi.unstubAllGlobals());

it("uses authenticated separation endpoints for upload, polling and review actions", async () => {
  const requests: Array<{ path: string; init?: RequestInit }> = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ path: String(input), init });
    return {
      ok: true,
      status: 200,
      json: async () => separationJob()
    };
  });
  vi.stubGlobal("fetch", fetchMock);

  const original = new File(["original"], "original.wav", { type: "audio/wav" });
  await createSeparation("admin-token", "song-1", original);
  await listSeparations("admin-token", "song-1");
  await getSeparation("admin-token", "job-1");
  await retrySeparation("admin-token", "job-1");
  await acceptSeparation("admin-token", "job-1");
  await rejectSeparation("admin-token", "job-1");

  expect(requests.map(({ path }) => path)).toEqual([
    "/api/v1/admin/songs/song-1/separations",
    "/api/v1/admin/separations?song_id=song-1",
    "/api/v1/admin/separations/job-1",
    "/api/v1/admin/separations/job-1/retry",
    "/api/v1/admin/separations/job-1/accept",
    "/api/v1/admin/separations/job-1/reject"
  ]);
  expect(requests[0].init?.body).toBeInstanceOf(FormData);
  expect(requests[0].init?.headers).toEqual({ Authorization: "Bearer admin-token" });
  for (const request of requests) {
    expect(request.init?.headers).toMatchObject({ Authorization: "Bearer admin-token" });
  }
});

function separationJob() {
  return {
    id: "job-1",
    song_id: "song-1",
    status: "succeeded",
    attempts: 1,
    model_name: "htdemucs",
    failure_code: null,
    failure_message: null,
    source_url: "/api/v1/media/source-1",
    vocals_url: "/api/v1/media/vocals-1",
    no_vocals_url: "/api/v1/media/no-vocals-1",
    approved_backing_track_id: null
  };
}
