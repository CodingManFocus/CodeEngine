import { test, expect, type Page } from "@playwright/test";
const source =
  'module hello;\n\nstate int joins = 0;\n\non PlayerJoinEvent event {\n    joins++;\n    event.getPlayer().sendMessage(Component.text("환영합니다!"));\n}\n\ncommand welcome permission "server.welcome" {\n    sender.sendMessage(Component.text("Joins: " + joins));\n    return true;\n}\n';
async function setup(page: Page) {
  const files = new Map([
    ["hello", { source, revision: "a".repeat(64) }],
    [
      "pulse",
      {
        source:
          'module pulse;\nevery 20 ticks {\n    ctx.plugin().getLogger().info("tick");\n}\n',
        revision: "b".repeat(64),
      },
    ],
  ]);
  const loaded = new Set(["hello"]);
  let revision = 0;
  let conflict = false;
  let pending = false;
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (
      message.type() === "error" &&
      /Content Security Policy|Refused to/.test(message.text())
    )
      errors.push(message.text());
  });
  await page.route("**/api/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      id = url.searchParams.get("id")!;
    const json = (value: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(value),
      });
    if (request.headers().authorization !== "Bearer test-token")
      return json({ error: "Session expired or unauthorized" }, 401);
    if (url.pathname === "/api/modules")
      return json({ modules: [...files.keys()], loaded: [...loaded] });
    if (url.pathname === "/api/file" && request.method() === "GET")
      return files.has(id)
        ? json(files.get(id))
        : json({ error: "File not found" }, 404);
    if (url.pathname === "/api/file") {
      const current = files.get(id),
        match = request.headers()["if-match"];
      if (conflict || match !== `"${current?.revision ?? "new"}"`)
        return json({ error: "Revision conflict" }, 409);
      const snapshot = {
        source: request.postData()!,
        revision: String(++revision).padStart(64, "0"),
      };
      files.set(id, snapshot);
      return json(snapshot);
    }
    if (url.pathname === "/api/operation") {
      const action = url.searchParams.get("action");
      if (action === "load" || action === "reload") loaded.add(id);
      if (action === "unload") loaded.delete(id);
      return json({ job: id + ":" + action }, 202);
    }
    if (url.pathname === "/api/job") {
      if (pending) return json({ status: "running", message: "Queued" });
      const [module, action] = id.split(":");
      if (files.get(module)?.source.includes("missingSymbol"))
        return json({
          status: "error",
          message: `${module}.ce:6: cannot find symbol\n  symbol: missingSymbol`,
        });
      return json({
        status: "success",
        message: `${action} succeeded: ${module}`,
      });
    }
    return json({ error: "not found" }, 404);
  });
  await page.goto("/#test-token");
  await expect(page.locator("#connection")).toHaveText("로컬 세션 연결됨");
  await page
    .locator("#moduleList")
    .getByRole("button", { name: "CE hello.ce" })
    .click();
  await expect(page.locator(".cm-content")).toBeVisible();
  return {
    files,
    errors,
    setConflict: (value: boolean) => {
      conflict = value;
    },
    setPending: (value: boolean) => {
      pending = value;
    },
  };
}
async function replaceSource(page: Page, value: string) {
  await page.locator(".cm-content").click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.insertText(value);
}
test("highlight, tab history, search, snippets, build errors, save conflicts and lifecycle", async ({
  page,
}) => {
  const fixture = await setup(page);
  expect(new URL(page.url()).hash).toBe("");
  const colors = await page.locator(".cm-content").evaluate((element) => {
    const spans = [...element.querySelectorAll("span")];
    return ["module", "int", "0", '"환영합니다!"'].map(
      (text) =>
        getComputedStyle(spans.find((span) => span.textContent === text)!)
          .color,
    );
  });
  expect(new Set(colors).size).toBe(4);
  await replaceSource(page, source + "\n// local draft");
  await page
    .locator("#moduleList")
    .getByRole("button", { name: "CE pulse.ce" })
    .click();
  await page.getByRole("tab", { name: "CE hello.ce" }).click();
  await expect(page.locator(".cm-content")).toContainText("// local draft");
  await page.locator(".cm-content").click();
  await page.keyboard.press("ControlOrMeta+z");
  await expect(page.locator(".cm-content")).not.toContainText("// local draft");
  await page.getByRole("button", { name: "검색 및 치환", exact: true }).click();
  await expect(page.locator(".cm-search")).toBeVisible();
  await page.keyboard.press("Escape");
  await replaceSource(page, source.replace("joins++;", "missingSymbol();"));
  await page.locator("#buildButton").click();
  await expect(page.locator(".problem")).toContainText("cannot find symbol");
  await page.locator(".problem").click();
  await expect(page.locator("#cursor")).toContainText("Ln 6");
  await expect(page.locator(".cm-lintRange-error")).toBeVisible();
  await expect(page.locator("#moduleStatus")).toContainText("실행 중");
  await replaceSource(page, source + "\n// conflict draft");
  fixture.setConflict(true);
  await page.locator("#saveButton").click();
  await expect(page.locator(".conflict")).toBeVisible();
  await expect(page.locator(".cm-content")).toContainText("conflict draft");
  const downloadPromise = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "로컬 사본 다운로드", exact: true })
    .first()
    .click();
  expect((await downloadPromise).suggestedFilename()).toBe("hello.ce");
  fixture.setConflict(false);
  await page.locator("#saveButton").click();
  await expect(page.locator(".conflict")).toHaveCount(0);
  await page.getByRole("button", { name: "문법 가이드", exact: true }).click();
  await page
    .locator(".reference article")
    .filter({ hasText: "주기 작업" })
    .getByRole("button")
    .click();
  await expect(page.locator(".cm-content")).toContainText("every 20 ticks");
  await page.getByRole("button", { name: "가이드 닫기" }).click();
  await replaceSource(page, source);
  fixture.setPending(true);
  await page.locator("#applyButton").click();
  await expect(page.locator("#saveButton")).toBeDisabled();
  await expect(page.getByRole("tab", { name: "CE pulse.ce" })).toBeDisabled();
  fixture.setPending(false);
  await expect(page.locator("#saveButton")).toBeEnabled();
  await page.locator("#unloadButton").click();
  await expect(page.locator("#moduleStatus")).toHaveText("미실행");
  await page.locator("#applyButton").click();
  await expect(page.locator("#moduleStatus")).toHaveText("실행 중");
  expect(fixture.errors).toEqual([]);
  await page.screenshot({ path: "test-results/studio-desktop.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".cm-content")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: "파일 탐색기" }).click();
  await expect(page.locator("#moduleList")).toBeVisible();
  await page
    .locator("#moduleList")
    .getByRole("button", { name: "CE pulse.ce" })
    .click();
  await expect(page.locator("#filename")).toContainText("pulse.ce");
  await page.screenshot({ path: "test-results/studio-mobile.png" });
});
test("create, cancel, dirty-close guard and reconnect retain edits", async ({
  page,
}) => {
  const fixture = await setup(page);
  await page.getByRole("button", { name: "새 모듈", exact: true }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "새 모듈", exact: true }).click();
  await page.locator("#dialogInput").fill("newmodule");
  await page.getByRole("button", { name: "만들기", exact: true }).click();
  await expect(page.locator("#filename")).toContainText("newmodule.ce");
  expect(fixture.files.has("newmodule")).toBe(true);
  await replaceSource(page, "module newmodule;\n// draft");
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("button", { name: "newmodule.ce 닫기" }).click();
  await expect(
    page.getByRole("tab", { name: "CE newmodule.ce" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "세션 연결", exact: true }).click();
  await page.locator("#dialogInput").fill("wrong");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.locator(".dialog-error")).toBeVisible();
  await page.locator("#dialogInput").fill("test-token");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.locator(".cm-content")).toContainText("// draft");
  expect(
    await page.evaluate(() => [
      ...Object.keys(localStorage),
      ...Object.keys(sessionStorage),
    ]),
  ).toEqual([]);
  expect(fixture.errors).toEqual([]);
});
