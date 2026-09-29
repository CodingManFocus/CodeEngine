import type {
  IntelligenceStatus,
  QueryKind,
  QueryResult,
  WorkerRequest,
  WorkerResponse,
} from "./protocol";

export class IntelligenceClient {
  status: IntelligenceStatus = {
    phase: "idle",
    message: "연결 후 API를 준비합니다.",
  };
  private worker?: Worker;
  private sequence = 0;
  private pending = new Map<
    number,
    { resolve: (value: unknown) => void; timer: ReturnType<typeof setTimeout> }
  >();
  private listeners = new Set<(status: IntelligenceStatus) => void>();
  subscribe(listener: (status: IntelligenceStatus) => void) {
    this.listeners.add(listener);
    listener(this.status);
    return () => {
      this.listeners.delete(listener);
    };
  }
  private publish(status: IntelligenceStatus) {
    this.status = status;
    this.listeners.forEach((listener) => listener(status));
  }
  start(authorization: string, retry = false) {
    this.stop();
    this.publish({ phase: "loading", message: "서버 API 준비 중…" });
    try {
      const worker = new Worker("/intelligence-worker.js");
      this.worker = worker;
      worker.onmessage = ({ data }: MessageEvent<WorkerResponse>) => {
        if (data.kind === "status") {
          this.publish(data.status);
          return;
        }
        const pending = this.pending.get(data.id);
        if (!pending) return;
        clearTimeout(pending.timer);
        this.pending.delete(data.id);
        pending.resolve(data.kind === "result" ? data.value : null);
      };
      worker.onerror = () => {
        this.stop();
        this.publish({
          phase: "error",
          message: "브라우저 코드 분석기를 시작하지 못했습니다.",
        });
      };
      worker.postMessage({
        kind: "initialize",
        authorization,
        retry,
      } satisfies WorkerRequest);
    } catch (error) {
      this.publish({
        phase: "error",
        message: error instanceof Error ? error.message : String(error),
      });
    }
  }
  query<K extends QueryKind>(
    kind: K,
    source: string,
    position = 0,
  ): Promise<QueryResult<K> | null> {
    if (
      !this.worker ||
      this.status.phase !== "ready" ||
      this.pending.size >= 32
    )
      return Promise.resolve(null);
    const id = ++this.sequence;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve(null);
      }, 10000);
      this.pending.set(id, {
        resolve: (value) => resolve(value as QueryResult<K> | null),
        timer,
      });
      this.worker!.postMessage({
        kind,
        id,
        source,
        position,
      } satisfies WorkerRequest);
    });
  }
  stop() {
    this.worker?.terminate();
    this.worker = undefined;
    for (const request of this.pending.values()) {
      clearTimeout(request.timer);
      request.resolve(null);
    }
    this.pending.clear();
    this.publish({ phase: "idle", message: "연결 후 API를 준비합니다." });
  }
}
