async (page) => {
  const environment =
    typeof process === "undefined" || !process.env ? {} : process.env;
  const currentUrl = page.url();
  const baseUrl =
    environment.VOCAEASE_E2E_BASE_URL ||
    (currentUrl.startsWith("http")
      ? currentUrl.match(/^https?:\/\/[^/]+/)?.[0]
      : "http://127.0.0.1:8080");
  const username = environment.VOCAEASE_E2E_ADMIN_USERNAME || "admin";
  const password =
    environment.VOCAEASE_E2E_ADMIN_PASSWORD || "admin888888";
  const participantQuery =
    environment.VOCAEASE_E2E_PARTICIPANT_QUERY || "DEMO-001";
  const participantName =
    environment.VOCAEASE_E2E_PARTICIPANT_NAME || "虚构演示参与者";
  const songTitle =
    environment.VOCAEASE_E2E_SONG_TITLE || "一期内部测试示例曲";
  const timeout = 20_000;
  const consoleErrors = [];

  const invariant = (condition, message) => {
    if (!condition) throw new Error(`E2E 验收失败：${message}`);
  };

  page.on("console", (message) => {
    const text = message.text();
    const isKnownAntDeprecation =
      text.startsWith("Warning: [antd: List]") &&
      text.includes("will be removed in next major version");
    if (message.type() === "error" && !isKnownAntDeprecation) {
      consoleErrors.push(text);
    }
  });

  await page.context().clearCookies();
  await page.addInitScript(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });
  await page.goto(baseUrl, { waitUntil: "domcontentloaded" });
  const usernameInput = page.getByLabel("管理员账号");
  const passwordInput = page.getByLabel("管理员密码");
  invariant(
    (await usernameInput.getAttribute("autocomplete")) === "username",
    "管理员账号缺少 username 自动填充语义"
  );
  invariant(
    (await passwordInput.getAttribute("autocomplete")) === "current-password",
    "管理员密码缺少 current-password 自动填充语义"
  );
  await usernameInput.fill(username);
  await passwordInput.fill(password);
  await page.getByRole("button", { name: /登\s*录/ }).click();
  await page
    .getByRole("heading", { name: "研究管理后台" })
    .waitFor({ state: "visible", timeout });

  await page.getByRole("tab", { name: "参与者" }).click();
  await page.getByRole("searchbox", { name: "搜索参与者" }).fill(participantQuery);
  await page.getByRole("button", { name: /搜\s*索/ }).click();
  await page
    .getByText(participantName, { exact: true })
    .waitFor({ state: "visible", timeout });

  await page.getByRole("tab", { name: "研究曲库" }).click();
  await page
    .getByText(songTitle, { exact: true })
    .first()
    .waitFor({ state: "visible", timeout });

  await page.getByRole("tab", { name: "声音实验室" }).click();
  const sessionButtons = page.locator(".session-nav");
  await sessionButtons.first().waitFor({ state: "visible", timeout });
  invariant(
    (await sessionButtons.count()) > 0,
    "干净演示环境中缺少合成演唱会话"
  );

  const protectedRequests = [];
  const protectedResponses = [];
  page.on("request", (request) => protectedRequests.push(request));
  page.on("response", (response) => protectedResponses.push(response));
  const labResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes("/api/v1/admin/singing-sessions/") &&
      response.url().endsWith("/lab") &&
      response.ok(),
    { timeout }
  );

  await sessionButtons.first().click();
  const labResponse = await labResponsePromise;
  const labPayload = await labResponse.json();
  await page
    .getByRole("img", { name: /真实波形/ })
    .waitFor({ state: "visible", timeout });
  await page
    .getByLabel("原始人声轨")
    .waitFor({ state: "visible", timeout });
  await page
    .getByRole("img", { name: /原始人声频谱图/ })
    .waitFor({ state: "visible", timeout });

  const requestKey = (url) => {
    const absolute = url.startsWith("http")
      ? url
      : `${baseUrl}${url.startsWith("/") ? "" : "/"}${url}`;
    return absolute.replace(/^https?:\/\/[^/]+/, "");
  };
  const rawVoiceKey = requestKey(labPayload.raw_voice_url);
  const spectrogramKey = requestKey(labPayload.spectrogram_url);
  const rawVoiceRequest = protectedRequests.find(
    (request) => requestKey(request.url()) === rawVoiceKey
  );
  const spectrogramRequest = protectedRequests.find(
    (request) => requestKey(request.url()) === spectrogramKey
  );
  const rawVoiceResponse = protectedResponses.find(
    (response) => requestKey(response.url()) === rawVoiceKey
  );
  const spectrogramResponse = protectedResponses.find(
    (response) => requestKey(response.url()) === spectrogramKey
  );

  invariant(rawVoiceRequest, "未请求声音实验室返回的原始人声地址");
  invariant(spectrogramRequest, "未请求声音实验室返回的频谱图地址");
  invariant(
    rawVoiceRequest.headers().authorization?.startsWith("Bearer "),
    "原始人声请求未携带管理员鉴权"
  );
  invariant(
    spectrogramRequest.headers().authorization?.startsWith("Bearer "),
    "频谱请求未携带管理员鉴权"
  );
  invariant(rawVoiceResponse?.ok(), "原始人声请求未成功");
  invariant(spectrogramResponse?.ok(), "频谱图请求未成功");
  invariant(
    (await page.locator("[data-waveform-point]").count()) > 0,
    "声音实验室没有绘制真实波形包络"
  );

  const visibleAutocompleteIssues = await page.locator("input:visible").evaluateAll(
    (inputs) =>
      inputs
        .filter(
          (input) =>
            input.type !== "file" &&
            !input.autocomplete &&
            !input.getAttribute("aria-label") &&
            !input.getAttribute("aria-labelledby")
        )
        .map((input) => input.name || input.id || input.type)
  );
  invariant(
    visibleAutocompleteIssues.length === 0,
    `存在未标识的可见输入：${visibleAutocompleteIssues.join(", ")}`
  );
  invariant(
    consoleErrors.length === 0,
    `浏览器控制台出现错误：${consoleErrors.join(" | ")}`
  );

  return {
    participant: participantQuery,
    song: songTitle,
    sessions: await sessionButtons.count(),
    protectedMediaRequests: 2
  };
}
