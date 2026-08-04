import { afterEach, expect, it, vi } from "vitest";

import { listAuditEvents, listParticipants, updateParticipant } from "./admin-api";

afterEach(() => vi.unstubAllGlobals());

it("encodes participant search, profile changes and every audit filter", async () => {
  const requests: Array<{ path: string; init?: RequestInit }> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ path: String(input), init });
      return {
        ok: true,
        status: 200,
        json: async () => []
      };
    })
  );

  await listParticipants("admin-token", "张 三/R-01");
  await updateParticipant("admin-token", "participant-1", {
    name: "张三",
    phone: "13900000000",
    research_code: "R-002",
    active: false
  });
  await listAuditEvents("admin-token", {
    actor_account_id: "11111111-1111-1111-1111-111111111111",
    object_type: "participant",
    action: "participant.updated",
    created_from: "2026-08-01T00:00:00.000Z",
    created_to: "2026-08-04T23:59:59.000Z"
  });

  expect(requests[0].path).toBe(
    "/api/v1/admin/participants?q=%E5%BC%A0%20%E4%B8%89%2FR-01"
  );
  expect(requests[1].path).toBe("/api/v1/admin/participants/participant-1");
  expect(requests[1].init?.method).toBe("PATCH");
  expect(JSON.parse(String(requests[1].init?.body))).toEqual({
    name: "张三",
    phone: "13900000000",
    research_code: "R-002",
    active: false
  });
  const auditUrl = new URL(requests[2].path, "http://localhost");
  expect(Object.fromEntries(auditUrl.searchParams)).toEqual({
    actor_account_id: "11111111-1111-1111-1111-111111111111",
    object_type: "participant",
    action: "participant.updated",
    created_from: "2026-08-01T00:00:00.000Z",
    created_to: "2026-08-04T23:59:59.000Z"
  });
  for (const request of requests) {
    expect(request.init?.headers).toMatchObject({ Authorization: "Bearer admin-token" });
  }
});
