import type { IntelligenceEngine } from "./engine";

export interface Artifact {
  id: string;
  name: string;
  sha256: string;
  size: number;
  url: string;
}
export interface Manifest {
  status: "loading" | "ready" | "error";
  message: string;
  version?: string;
  requestedVersion?: string;
  resolution?: "exact" | "compatible-snapshot";
  javaVersion?: number;
  artifacts: Artifact[];
}
export interface IntelligenceStatus {
  phase: "idle" | "loading" | "ready" | "error";
  message: string;
  version?: string;
  approximate?: boolean;
}
export type QueryKind = "complete" | "hover" | "signature" | "diagnostics";
export type QueryResult<K extends QueryKind> = ReturnType<
  IntelligenceEngine[K]
>;
export type WorkerRequest =
  | { kind: "initialize"; authorization: string; retry?: boolean }
  | { kind: QueryKind; id: number; source: string; position: number };
export type WorkerResponse =
  | { kind: "status"; status: IntelligenceStatus }
  | { kind: "result"; id: number; value: QueryResult<QueryKind> }
  | { kind: "error"; id: number; message: string };
