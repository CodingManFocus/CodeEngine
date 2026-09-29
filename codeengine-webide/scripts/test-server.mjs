// Test fixture only; production uses the plugin's authenticated WebIdeServer.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
const files = new Map([
  ["/", ["index.html", "text/html"]],
  ["/app.js", ["app.js", "text/javascript"]],
  ["/intelligence-worker.js", ["intelligence-worker.js", "text/javascript"]],
  ["/style.css", ["style.css", "text/css"]],
]);
createServer(async (request, response) => {
  const asset = files.get(new URL(request.url, "http://localhost").pathname);
  if (!asset) {
    response.writeHead(404).end();
    return;
  }
  const nonce = "studioBrowserTestNonce";
  response.setHeader("Content-Type", asset[1] + "; charset=utf-8");
  response.setHeader(
    "Content-Security-Policy",
    `default-src 'self'; script-src 'self'; worker-src 'self'; style-src 'self' 'nonce-${nonce}'; style-src-attr 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
  );
  let content = await readFile(
    new URL("../dist/" + asset[0], import.meta.url),
    "utf8",
  );
  response.end(content.replace("__CE_STYLE_NONCE__", nonce));
}).listen(4173, "127.0.0.1");
