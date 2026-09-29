import { IntelligenceEngine } from "./engine";
import { parseJar } from "./jar";
import { cachedJar, cacheJar, pruneCache } from "./cache";
import type { JavaClass } from "./types";
import type {
  Artifact,
  Manifest,
  IntelligenceStatus,
  WorkerRequest,
  WorkerResponse,
} from "./protocol";

const scope = globalThis as unknown as {
  onmessage: (event: MessageEvent<WorkerRequest>) => void;
  postMessage: (message: WorkerResponse) => void;
};
let engine: IntelligenceEngine | undefined;
const maxJarBytes = 64 * 1024 * 1024;
const maxBundleBytes = 256 * 1024 * 1024;
function status(value: IntelligenceStatus) {
  scope.postMessage({ kind: "status", status: value });
}
async function digest(bytes: ArrayBuffer) {
  return Array.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
}
async function archive(
  artifact: Artifact,
  authorization: string,
): Promise<ArrayBuffer> {
  if (
    !/^[a-f0-9]{64}$/.test(artifact.sha256) ||
    !Number.isSafeInteger(artifact.size) ||
    artifact.size <= 0 ||
    artifact.size > maxJarBytes
  )
    throw new Error("API JAR의 크기 또는 해시가 잘못되었습니다.");
  const cached = await cachedJar(artifact.sha256);
  if (
    cached instanceof ArrayBuffer &&
    cached.byteLength === artifact.size &&
    (await digest(cached)) === artifact.sha256
  )
    return cached;
  const url = new URL(artifact.url, location.origin);
  if (
    url.origin !== location.origin ||
    url.pathname !== "/api/intelligence/artifact"
  )
    throw new Error("API JAR 주소가 잘못되었습니다.");
  const response = await fetch(url, {
    headers: { Authorization: authorization },
    signal: AbortSignal.timeout(60000),
  });
  if (!response.ok || !response.body)
    throw new Error(`API JAR 전송 실패 (${response.status})`);
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > artifact.size)
        throw new Error("API JAR 크기가 명세와 다릅니다.");
      chunks.push(value);
    }
  } catch (error) {
    await reader.cancel();
    throw error;
  } finally {
    reader.releaseLock();
  }
  if (length !== artifact.size)
    throw new Error("API JAR 전송이 완료되지 않았습니다.");
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  if ((await digest(bytes.buffer)) !== artifact.sha256)
    throw new Error("API JAR 무결성 검증에 실패했습니다.");
  await cacheJar(artifact.sha256, bytes.buffer);
  return bytes.buffer;
}
async function initialize(authorization: string, retry = false) {
  engine = undefined;
  try {
    if (retry) {
      const response = await fetch("/api/intelligence/retry", {
        method: "POST",
        headers: { Authorization: authorization },
        signal: AbortSignal.timeout(15000),
      });
      if (!response.ok)
        throw new Error(`API 준비 재시도 실패 (${response.status})`);
    }
    const deadline = Date.now() + 300000;
    let manifest: Manifest;
    while (true) {
      status({ phase: "loading", message: "서버 API 준비 중…" });
      const response = await fetch("/api/intelligence", {
        headers: { Authorization: authorization },
        signal: AbortSignal.timeout(15000),
      });
      if (!response.ok)
        throw new Error(`API 정보를 가져올 수 없습니다 (${response.status}).`);
      manifest = (await response.json()) as Manifest;
      if (manifest.status === "error")
        throw new Error(manifest.message || "서버 API 준비 실패");
      if (manifest.status === "ready") break;
      if (manifest.status !== "loading")
        throw new Error("알 수 없는 API 준비 상태입니다.");
      if (Date.now() >= deadline)
        throw new Error("API 준비 시간이 초과되었습니다. 다시 시도하세요.");
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    if (
      !Array.isArray(manifest.artifacts) ||
      !manifest.artifacts.length ||
      manifest.artifacts.length > 128 ||
      manifest.artifacts.reduce((sum, item) => sum + item.size, 0) >
        maxBundleBytes
    )
      throw new Error("API 묶음이 허용 크기를 초과했습니다.");
    const classes = new Map<string, JavaClass>();
    for (const [index, artifact] of manifest.artifacts.entries()) {
      status({
        phase: "loading",
        message: `API 분석 중 ${index + 1}/${manifest.artifacts.length} · ${artifact.name}`,
      });
      const bytes = await archive(artifact, authorization);
      for (const type of await parseJar(bytes, {
        javaVersion: manifest.javaVersion,
      })) {
        // Classpath order, including explicitly selected API version, wins.
        if (!classes.has(type.name)) classes.set(type.name, type);
      }
    }
    engine = new IntelligenceEngine([...classes.values()]);
    status({
      phase: "ready",
      message: `${classes.size.toLocaleString()}개 타입 준비됨`,
      version: manifest.version,
      approximate: manifest.resolution === "compatible-snapshot",
    });
    await pruneCache(manifest.artifacts.map((item) => item.sha256));
  } catch (error) {
    status({
      phase: "error",
      message: error instanceof Error ? error.message : String(error),
    });
  }
}
scope.onmessage = ({ data }) => {
  if (data.kind === "initialize") {
    void initialize(data.authorization, data.retry);
    return;
  }
  try {
    const value = !engine
      ? data.kind === "diagnostics"
        ? []
        : null
      : data.kind === "diagnostics"
        ? engine.diagnostics(data.source)
        : engine[data.kind](data.source, data.position);
    scope.postMessage({ kind: "result", id: data.id, value });
  } catch (error) {
    scope.postMessage({
      kind: "error",
      id: data.id,
      message: error instanceof Error ? error.message : String(error),
    });
  }
};
