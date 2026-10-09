import { test, expect, type Page, type BrowserContext } from "@playwright/test";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";

const jar = Buffer.from(
  readFileSync(
    new URL("./fixtures/browser-api.jar.base64", import.meta.url),
    "utf8",
  ),
  "base64",
);
const sha256 = createHash("sha256").update(jar).digest("hex");
async function fixture(
  context: BrowserContext,
  mode: "ready" | "loading" | "error" | "corrupt" = "ready",
) {
  const requests: { path: string; method: string }[] = [];
  let downloads = 0;
  let current = mode;
  await context.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    requests.push({ path, method: request.method() });
    const json = (value: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(value),
      });
    if (request.headers().authorization !== "Bearer intelligence-test")
      return json({ error: "Unauthorized" }, 401);
    if (path === "/api/modules")
      return json({ modules: ["hello"], loaded: [] });
    if (path === "/api/file")
      return json({
        source: "module hello;\non PlayerJoinEvent event {\n}\n",
        revision: sha256,
      });
    if (path === "/api/intelligence/retry") return json({ status: "loading" });
    if (path === "/api/intelligence")
      return json({
        status: current === "corrupt" ? "ready" : current,
        message:
          current === "error" ? "fixture download unavailable" : "preparing",
        version: "1.21.11-R0.1-SNAPSHOT",
        resolution: "compatible-snapshot",
        javaVersion: 21,
        artifacts: [
          {
            id: "paper",
            name: "paper-api.jar",
            sha256,
            size: jar.length,
            url: "/api/intelligence/artifact?id=paper",
          },
        ],
      });
    if (path === "/api/intelligence/artifact") {
      downloads++;
      const bytes = Buffer.from(jar);
      if (current === "corrupt") bytes[30] ^= 1;
      return route.fulfill({
        status: 200,
        contentType: "application/java-archive",
        body: bytes,
      });
    }
    return json({ error: "Unexpected endpoint" }, 404);
  });
  return {
    requests,
    downloads: () => downloads,
    setMode: (mode: typeof current) => {
      current = mode;
    },
  };
}
async function open(page: Page, suffix = "") {
  await page.goto(`/${suffix}#intelligence-test`);
  await expect(page.locator("#connection")).toHaveText("로컬 세션 연결됨");
  await page
    .locator("#moduleList")
    .getByRole("button", { name: "CE hello.ce" })
    .click();
}
async function source(page: Page, text: string) {
  await page.locator(".cm-content").click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.insertText(text);
}
test("real JAR is indexed in worker under CSP; inherited completion, signatures and cache stay in browser", async ({
  page,
  context,
}) => {
  const mock = await fixture(context);
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (/Content Security Policy|Refused to/.test(message.text()))
      errors.push(message.text());
  });
  await open(page);
  await expect(page.locator("#intelligenceStatus")).toContainText(
    "브라우저 코드 분석",
  );
  await expect(page.locator("#intelligenceStatus")).toContainText("SNAPSHOT");
  expect(mock.downloads()).toBe(1);
  const requestsAfterLoad = mock.requests.length;
  await source(
    page,
    "module hello;\non PlayerJoinEvent event {\n    event.getPlayer().",
  );
  await page.keyboard.type("get");
  await expect(
    page.getByRole("option").filter({ hasText: "getName" }),
  ).toBeVisible();
  await expect(
    page.getByRole("option").filter({ hasText: "getHealth" }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await source(
    page,
    "module hello;\non PlayerJoinEvent event {\n    event.getPlayer().sendMessage(",
  );
  await expect(page.locator(".ce-api-tooltip")).toContainText("sendMessage");
  await expect(page.locator(".ce-api-tooltip")).toContainText("String");
  await source(
    page,
    "module hello;\nuse org.bukkit.entity.MissingType;\non PlayerJoinEvent event {\n}\n",
  );
  await expect(
    page.locator(".cm-lintRange-warning, .cm-lintRange-error"),
  ).toBeVisible();
  expect(mock.requests.slice(requestsAfterLoad)).toEqual([]);
  await source(page, "module hello;\nuse org.bukkit.entity.");
  await page.keyboard.type("Pla");
  await expect(
    page.getByRole("option").filter({ hasText: "Player" }),
  ).toBeVisible();
  await page.screenshot({ path: "test-results/intelligence-desktop.png" });
  await open(page, "?cached=1");
  await expect(page.locator("#intelligenceStatus")).toContainText(
    "브라우저 코드 분석",
  );
  expect(mock.downloads()).toBe(1);
  expect(errors).toEqual([]);
});
test("download failure, retry and loading do not lock source editing", async ({
  page,
  context,
}) => {
  const mock = await fixture(context, "loading");
  await open(page);
  await source(page, "module hello;\n// editing while downloading");
  await expect(page.locator("#saveButton")).toBeEnabled();
  await expect(page.locator(".cm-content")).toContainText(
    "editing while downloading",
  );
  mock.setMode("error");
  await expect(page.locator("#intelligenceStatus")).toContainText(
    "fixture download unavailable",
  );
  mock.setMode("ready");
  await page.getByRole("button", { name: "API 다시 준비" }).click();
  await expect(page.locator("#intelligenceStatus")).toContainText(
    "브라우저 코드 분석",
  );
  await expect(page.locator(".cm-content")).toContainText(
    "editing while downloading",
  );
});
test("corrupt JAR is rejected before intelligence is enabled", async ({
  page,
  context,
}) => {
  await fixture(context, "corrupt");
  await open(page);
  await expect(page.locator("#intelligenceStatus")).toContainText(
    "무결성 검증",
  );
  await expect(page.locator("#saveButton")).toBeEnabled();
});
