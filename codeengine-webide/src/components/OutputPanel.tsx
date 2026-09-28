import { useRef, useEffect } from "react";
import type { DocumentTab } from "./Editor";
export interface LogEntry {
  time: string;
  message: string;
  kind: "info" | "success" | "error";
}
interface Props {
  panel: "output" | "problems";
  setPanel: (value: "output" | "problems") => void;
  current?: DocumentTab;
  logs: LogEntry[];
  onClear: () => void;
  onJump: (line: number) => void;
}
export function OutputPanel({
  panel,
  setPanel,
  current,
  logs,
  onClear,
  onJump,
}: Props) {
  const outputRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    outputRef.current?.scrollTo({ top: outputRef.current.scrollHeight });
  }, [logs]);
  return (
    <section className="bottom-panel">
      <div className="panel-tabs">
        <button
          className={panel === "output" ? "active" : ""}
          onClick={() => setPanel("output")}
        >
          출력
        </button>
        <button
          className={panel === "problems" ? "active" : ""}
          onClick={() => setPanel("problems")}
        >
          문제 <span className="count">{current?.problems.length ?? 0}</span>
        </button>
        <span className="panel-hint">저장 → 빌드 검사 → 서버 적용</span>
        <button className="clear-output" onClick={onClear}>
          출력 지우기
        </button>
      </div>
      <div className="panel-content" ref={outputRef}>
        {panel === "output" ? (
          <div id="output" role="log" aria-live="polite">
            {logs.length ? (
              logs.map((entry, index) => (
                <div className={"log-line " + entry.kind} key={index}>
                  <time>{entry.time}</time>
                  <pre>{entry.message}</pre>
                </div>
              ))
            ) : (
              <div className="panel-empty">
                준비되었습니다. 빌드 결과와 서버 작업이 여기에 표시됩니다.
              </div>
            )}
          </div>
        ) : (
          <div>
            {current?.problems.length ? (
              current.problems.map((problem, index) => (
                <button
                  className="problem"
                  key={index}
                  onClick={() => onJump(problem.line)}
                >
                  <span>×</span>
                  <pre>{problem.message}</pre>
                  <small>
                    {current.id}.ce:{problem.line}
                  </small>
                </button>
              ))
            ) : (
              <div className="panel-empty">
                표시할 빌드 오류가 없습니다. 빌드 검사로 최신 코드를 확인하세요.
              </div>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
