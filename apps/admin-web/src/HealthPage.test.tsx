import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { HealthPage } from "./HealthPage";

describe("HealthPage", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("shows the shared service and every dependency as available", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          status: "healthy",
          dependencies: {
            database: "up",
            redis: "up",
            media_storage: "up"
          }
        })
      })
    );

    render(<HealthPage />);

    expect(await screen.findByText("服务运行正常")).toBeInTheDocument();
    expect(screen.getByText("PostgreSQL")).toBeInTheDocument();
    expect(screen.getByText("Redis")).toBeInTheDocument();
    expect(screen.getByText("媒体存储")).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith("/api/v1/health");
  });

  it("shows dependency details from a degraded health response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        json: async () => ({
          status: "degraded",
          dependencies: {
            database: "up",
            redis: "down",
            media_storage: "up"
          }
        })
      })
    );

    render(<HealthPage />);

    expect(await screen.findByText("部分服务不可用")).toBeInTheDocument();
    expect(screen.getByText("Redis").nextElementSibling).toHaveTextContent("不可用");
  });
});
