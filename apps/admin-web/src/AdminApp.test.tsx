import { cleanup, fireEvent, render, screen } from "@testing-library/react";
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

  expect(await screen.findByText("参与者管理")).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("姓名"), { target: { value: "测试参与者" } });
  fireEvent.change(screen.getByLabelText("手机号"), { target: { value: "13900000000" } });
  fireEvent.change(screen.getByLabelText("研究编号"), { target: { value: "R-001" } });
  fireEvent.click(screen.getByRole("button", { name: "创建参与者" }));

  expect(await screen.findByText("测试参与者")).toBeInTheDocument();
  expect(screen.getByText("初始密码待修改")).toBeInTheDocument();
});
