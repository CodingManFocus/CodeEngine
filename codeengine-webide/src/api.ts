export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export interface Snapshot {
  source: string;
  revision: string;
}
export interface ModuleList {
  modules: string[];
  loaded: string[];
}
export interface Job {
  status: "running" | "success" | "error";
  message: string;
}
export class StudioApi {
  constructor(private token = "") {}
  setToken(value: string) {
    this.token = value;
  }
  async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const response = await fetch("/api/" + path, {
      ...options,
      signal: AbortSignal.timeout(15000),
      headers: { ...options.headers, Authorization: "Bearer " + this.token },
    });
    const result = await response.json();
    if (!response.ok)
      throw new ApiError(
        result.error || `요청 실패 (${response.status})`,
        response.status,
      );
    return result;
  }
  list() {
    return this.request<ModuleList>("modules");
  }
  read(id: string) {
    return this.request<Snapshot>("file?id=" + encodeURIComponent(id));
  }
  save(id: string, source: string, revision: string) {
    return this.request<Snapshot>("file?id=" + encodeURIComponent(id), {
      method: "PUT",
      headers: {
        "If-Match": `"${revision}"`,
        "Content-Type": "text/plain; charset=utf-8",
      },
      body: source,
    });
  }
  rename(id: string, targetId: string, revision: string) {
    return this.request<Snapshot>(
      `file/rename?id=${encodeURIComponent(id)}&to=${encodeURIComponent(targetId)}`,
      { method: "POST", headers: { "If-Match": `"${revision}"` } },
    );
  }
  delete(id: string, revision: string) {
    return this.request<{ deleted: string }>(
      "file?id=" + encodeURIComponent(id),
      {
        method: "DELETE",
        headers: { "If-Match": `"${revision}"` },
      },
    );
  }
  start(id: string, action: string) {
    return this.request<{ job: string }>(
      `operation?id=${encodeURIComponent(id)}&action=${action}`,
      { method: "POST" },
    );
  }
  job(id: string) {
    return this.request<Job>("job?id=" + encodeURIComponent(id));
  }
}
export function initialToken() {
  const token = location.hash.slice(1);
  history.replaceState(null, "", location.pathname + location.search);
  return token;
}
