import { useEffect, useRef, useState } from "react";
import type { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { openSearchPanel } from "@codemirror/search";
import { snippet } from "@codemirror/autocomplete";
import { StudioApi, ApiError, initialToken } from "./api";
import { parseProblems } from "./diagnostics";
import { LanguageGuide } from "./components/LanguageGuide";
import { Welcome } from "./components/Welcome";
import { OutputPanel, type LogEntry } from "./components/OutputPanel";
import { Editor, type DocumentTab } from "./components/Editor";
import { Dialog } from "./components/Dialog";
import { IntelligenceClient } from "./intelligence/client";
import type { IntelligenceStatus } from "./intelligence/protocol";

const bootstrapToken = initialToken();
const api = new StudioApi(bootstrapToken);
const actionLabels: Record<string, string> = {
  build: "빌드 검사",
  load: "서버 적용",
  reload: "다시 적용",
  unload: "모듈 해제",
};
export default function App() {
  const [intelligence] = useState(() => new IntelligenceClient());
  const [intelligenceStatus, setIntelligenceStatus] =
    useState<IntelligenceStatus>(intelligence.status);
  const [connectionGeneration, setConnectionGeneration] = useState(0);
  const [modules, setModules] = useState<string[]>([]),
    [loaded, setLoaded] = useState<string[]>([]);
  const [tabs, setTabs] = useState<DocumentTab[]>([]),
    [active, setActive] = useState<string | null>(null);
  const [connected, setConnected] = useState(false),
    [busy, setBusy] = useState("");
  const [dialog, setDialog] = useState<
    "connect" | "create" | "rename" | "delete" | null
  >(bootstrapToken ? null : "connect");
  const [input, setInput] = useState(""),
    [dialogError, setDialogError] = useState("");
  const [filter, setFilter] = useState(""),
    [wrap, setWrap] = useState(false),
    [guide, setGuide] = useState(false);
  const [sidebar, setSidebar] = useState(false),
    [panel, setPanel] = useState<"output" | "problems">("output");
  const [position, setPosition] = useState({ line: 1, column: 1 }),
    [logs, setLogs] = useState<LogEntry[]>([]);
  const [notice, setNotice] = useState("");
  const tabRef = useRef(tabs);
  tabRef.current = tabs;
  const activeRef = useRef(active);
  activeRef.current = active;
  const viewRef = useRef<EditorView | null>(null),
    lock = useRef(false);
  const current = tabs.find((tab) => tab.id === active);
  const dirty = (tab: DocumentTab) => tab.source !== tab.savedSource;
  const changed = tabs.filter(dirty).length;
  function log(message: string, kind: LogEntry["kind"] = "info") {
    setLogs((previous) =>
      [
        ...previous,
        {
          time: new Date().toLocaleTimeString("ko-KR", { hour12: false }),
          message: message.slice(-30000),
          kind,
        },
      ].slice(-100),
    );
  }
  function updateTabs() {
    setTabs((previous) => [...previous]);
  }
  async function refresh() {
    const state = await api.list();
    setModules(state.modules);
    setLoaded(state.loaded);
    setConnected(true);
  }
  async function run(label: string, task: () => Promise<void>) {
    if (lock.current) return;
    lock.current = true;
    setBusy(label);
    setNotice("");
    try {
      await task();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (error instanceof ApiError && error.status === 401) {
        setConnected(false);
        setDialog("connect");
      }
      setNotice(message);
      log(message, "error");
      setPanel("output");
      setDialogError(message);
    } finally {
      lock.current = false;
      setBusy("");
    }
  }
  useEffect(() => {
    if (bootstrapToken) void run("연결 중", refresh);
  }, []);
  useEffect(
    () => intelligence.subscribe(setIntelligenceStatus),
    [intelligence],
  );
  useEffect(() => {
    if (connected) intelligence.start(api.authorization);
    return () => intelligence.stop();
  }, [connected, connectionGeneration, intelligence]);
  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (tabRef.current.some(dirty)) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, []);
  async function openModule(id: string) {
    if (tabRef.current.some((tab) => tab.id === id)) {
      setActive(id);
      setSidebar(false);
      return;
    }
    await run("모듈 여는 중", async () => {
      const snapshot = await api.read(id);
      const tab: DocumentTab = {
        id,
        source: snapshot.source,
        savedSource: snapshot.source,
        revision: snapshot.revision,
        scrollTop: 0,
        scrollLeft: 0,
        problems: [],
        conflict: false,
      };
      setTabs((previous) => [...previous, tab]);
      setActive(id);
      setSidebar(false);
    });
  }
  function closeTab(tab: DocumentTab) {
    if (
      lock.current ||
      (dirty(tab) && !confirm(`${tab.id}.ce의 저장하지 않은 변경을 버릴까요?`))
    )
      return;
    const remaining = tabs.filter((item) => item.id !== tab.id);
    setTabs(remaining);
    if (active === tab.id) setActive(remaining.at(-1)?.id ?? null);
  }
  async function save(tab: DocumentTab) {
    if (new TextEncoder().encode(tab.source).length > 262144)
      throw new Error("모듈 소스는 UTF-8 기준 256 KiB까지 저장할 수 있습니다.");
    try {
      const snapshot = await api.save(tab.id, tab.source, tab.revision);
      tab.savedSource = snapshot.source;
      tab.revision = snapshot.revision;
      tab.conflict = false;
      updateTabs();
      log(`${tab.id}.ce 저장 완료`, "success");
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        tab.conflict = true;
        updateTabs();
        throw new Error(
          "서버 파일이 변경되었습니다. 편집 내용은 유지했습니다. 로컬 사본을 내려받거나 서버 파일을 다시 여세요.",
        );
      }
      throw error;
    }
  }
  const saveCurrent = () => {
    const tab = tabRef.current.find((tab) => tab.id === activeRef.current);
    if (tab && connected) void run("저장 중", () => save(tab));
  };
  async function operation(action: string) {
    const tab = tabRef.current.find((tab) => tab.id === activeRef.current);
    if (!tab || !connected) return;
    await run(actionLabels[action], async () => {
      if (action !== "unload") {
        await save(tab);
        tab.problems = [];
        updateTabs();
      }
      const source = tab.source;
      const { job } = await api.start(tab.id, action);
      log(`${actionLabels[action]} 시작 · ${tab.id}`);
      setPanel("output");
      const deadline = Date.now() + 120000;
      while (Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 400));
        const result = await api.job(job);
        if (result.status === "running") continue;
        log(result.message, result.status);
        if (
          result.status === "error" &&
          action !== "unload" &&
          tab.source === source
        ) {
          tab.problems = parseProblems(result.message, tab.id);
          updateTabs();
          if (tab.problems.length) setPanel("problems");
        }
        await refresh();
        return;
      }
      throw new Error(
        "작업 응답 대기 시간이 지났습니다. 서버에서 계속 진행 중일 수 있습니다. 콘솔을 확인한 뒤 목록을 새로고침하세요.",
      );
    });
  }
  function onEdit(state: EditorState) {
    const tab = tabRef.current.find((tab) => tab.id === activeRef.current);
    if (!tab) return;
    const contentChanged = tab.state?.doc !== state.doc;
    tab.state = state;
    if (!contentChanged) return;
    const source = state.doc.toString();
    if (source !== tab.source) {
      tab.source = source;
      tab.problems = [];
      updateTabs();
    }
  }
  function download() {
    if (!current) return;
    const url = URL.createObjectURL(
      new Blob([current.source], { type: "text/plain;charset=utf-8" }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = current.id + ".ce";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function reloadFile() {
    if (
      !current ||
      !confirm(
        `${current.id}.ce를 서버 내용으로 다시 열까요? 현재 탭의 편집 내용은 사라집니다.`,
      )
    )
      return;
    void run("다시 여는 중", async () => {
      const snapshot = await api.read(current.id);
      viewRef.current?.dispatch({
        changes: {
          from: 0,
          to: viewRef.current.state.doc.length,
          insert: snapshot.source,
        },
        selection: { anchor: 0 },
      });
      current.source = snapshot.source;
      current.savedSource = snapshot.source;
      current.revision = snapshot.revision;
      current.conflict = false;
      current.problems = [];
      updateTabs();
      log(`${current.id}.ce를 서버에서 다시 열었습니다.`);
    });
  }
  function jump(lineNumber: number) {
    const view = viewRef.current;
    if (!view) return;
    const line = view.state.doc.line(
      Math.min(lineNumber, view.state.doc.lines),
    );
    view.dispatch({
      selection: { anchor: line.from },
      effects: EditorView.scrollIntoView(line.from, { y: "center" }),
    });
    view.focus();
  }
  function insertTemplate(code: string) {
    const view = viewRef.current;
    if (!view || busy) return;
    const end = view.state.doc.length;
    snippet("\n\n" + code)(view, null, end, end);
    view.focus();
  }
  function showDialog(type: "connect" | "create" | "rename" | "delete") {
    setInput(type === "rename" ? (current?.id ?? "") : "");
    setDialogError("");
    setDialog(type);
  }
  async function submitDialog(event: React.FormEvent) {
    event.preventDefault();
    setDialogError("");
    await run(
      dialog === "connect"
        ? "연결 중"
        : dialog === "create"
          ? "파일 생성 중"
          : dialog === "rename"
            ? "이름 변경 중"
            : "파일 삭제 중",
      async () => {
        if (dialog === "connect") {
          const value = input.trim();
          api.setToken(value.includes("#") ? value.split("#").at(-1)! : value);
          await refresh();
          setConnectionGeneration((previous) => previous + 1);
          setInput("");
          setDialog(null);
          log("로컬 세션 연결됨", "success");
        } else if (dialog === "rename" || dialog === "delete") {
          const tab = tabRef.current.find(
            (item) => item.id === activeRef.current,
          );
          if (!tab) throw new Error("먼저 파일을 선택하세요.");
          if (loaded.includes(tab.id))
            throw new Error("실행 중인 모듈은 먼저 해제하세요.");
          if (dialog === "rename") {
            if (dirty(tab))
              throw new Error("이름을 변경하기 전에 파일을 저장하세요.");
            const targetId = input.trim();
            if (!/^[a-z][a-z0-9_]{0,47}$/.test(targetId))
              throw new Error(
                "영문 소문자로 시작하고 숫자와 밑줄만 사용하세요 (최대 48자).",
              );
            if (targetId === tab.id)
              throw new Error("현재 이름과 다른 이름을 입력하세요.");
            const snapshot = await api.rename(tab.id, targetId, tab.revision);
            setTabs((previous) =>
              previous.map((item) =>
                item.id === tab.id
                  ? {
                      ...item,
                      id: targetId,
                      source: snapshot.source,
                      savedSource: snapshot.source,
                      revision: snapshot.revision,
                      state: undefined,
                      problems: [],
                      conflict: false,
                    }
                  : item,
              ),
            );
            setActive(targetId);
            log(`${tab.id}.ce → ${targetId}.ce 이름 변경 완료`, "success");
          } else {
            await api.delete(tab.id, tab.revision);
            const remaining = tabRef.current.filter(
              (item) => item.id !== tab.id,
            );
            setTabs(remaining);
            setActive(remaining.at(-1)?.id ?? null);
            log(`${tab.id}.ce 삭제 완료`, "success");
          }
          setDialog(null);
          setInput("");
          await refresh();
        } else {
          const id = input.trim();
          if (!/^[a-z][a-z0-9_]{0,47}$/.test(id))
            throw new Error(
              "소문자로 시작하는 영문·숫자·밑줄, 최대 48자로 입력하세요.",
            );
          const source = `module ${id};\n\non PlayerJoinEvent event {\n    event.getPlayer().sendMessage(Component.text("환영합니다!"));\n}\n`;
          const snapshot = await api.save(id, source, "new");
          setTabs((previous) => [
            ...previous,
            {
              id,
              source: snapshot.source,
              savedSource: snapshot.source,
              revision: snapshot.revision,
              scrollTop: 0,
              scrollLeft: 0,
              problems: [],
              conflict: false,
            },
          ]);
          setActive(id);
          setInput("");
          setDialog(null);
          setSidebar(false);
          await refresh();
          log(`${id}.ce 생성 완료`, "success");
        }
      },
    );
  }
  return (
    <div className="studio">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">
            ce<span>.</span>
          </span>
          <strong>Code Engine</strong>
          <span className="studio-label">STUDIO</span>
        </div>
        <div className="topbar-right">
          <span
            className={"connection " + (connected ? "online" : "")}
            id="connection"
          >
            <i />
            {connected ? "로컬 세션 연결됨" : "연결 필요"}
          </span>
          <button
            id="connectButton"
            disabled={!!busy}
            onClick={() => showDialog("connect")}
          >
            세션 연결
          </button>
        </div>
      </header>
      <div className="workspace">
        <aside className={"explorer " + (sidebar ? "mobile-open" : "")}>
          <div className="section-heading">
            <span>EXPLORER</span>
            <div>
              <button
                className="icon-button"
                id="refreshButton"
                title="목록 새로고침"
                aria-label="목록 새로고침"
                disabled={!!busy || !connected}
                onClick={() => void run("목록 새로고침", refresh)}
              >
                ↻
              </button>
              <button
                className="icon-button"
                id="newButton"
                title="새 .ce 파일 만들기"
                aria-label="새 .ce 파일 만들기"
                disabled={!!busy || !connected}
                onClick={() => showDialog("create")}
              >
                ＋ <span className="new-file-label">새 .ce 파일</span>
              </button>
            </div>
          </div>
          <div className="project-name">
            <span className="project-icon">⌘</span>
            <div>
              내 워크스페이스
              <small>
                {modules.length}개의 모듈 · {loaded.length}개 실행 중
              </small>
            </div>
          </div>
          <input
            className="file-filter"
            aria-label="모듈 검색"
            placeholder="모듈 검색…"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
          />
          <nav id="moduleList" aria-label="모듈 목록">
            {modules
              .filter((id) => id.includes(filter))
              .map((id) => (
                <button
                  key={id}
                  className={"file-row " + (active === id ? "selected" : "")}
                  disabled={!!busy}
                  onClick={() => void openModule(id)}
                >
                  <span className="ce-file">CE</span>
                  <span className="file-name">
                    {id}
                    <span className="extension">.ce</span>
                  </span>
                  {tabs.some((tab) => tab.id === id && dirty(tab)) && (
                    <span title="저장되지 않음" className="unsaved-dot" />
                  )}
                  {loaded.includes(id) && (
                    <span className="running-dot" title="실행 중" />
                  )}
                </button>
              ))}
          </nav>
          {connected && !modules.length && (
            <p className="sidebar-empty">
              아직 모듈이 없어요.
              <br />
              ‘새 .ce 파일’ 버튼으로 만들어 보세요.
            </p>
          )}
          <div className="explorer-bottom">
            <span className="eyebrow">YOUR CODE. YOUR SERVER.</span>
            <p>
              작성하고, 검사하고,
              <br />
              준비되면 적용하세요.
            </p>
            <button className="guide-button" onClick={() => setGuide(!guide)}>
              문법 가이드 <span>↗</span>
            </button>
          </div>
        </aside>
        <main className="workbench">
          <div className="tabbar">
            <button
              className="mobile-files icon-button"
              aria-label="파일 탐색기"
              aria-expanded={sidebar}
              onClick={() => setSidebar(!sidebar)}
            >
              ☰
            </button>
            <div className="tabs" role="tablist" aria-label="열린 모듈">
              {tabs.map((tab) => (
                <div
                  className={"tab " + (active === tab.id ? "active" : "")}
                  key={tab.id}
                >
                  <button
                    role="tab"
                    aria-selected={active === tab.id}
                    disabled={!!busy}
                    onClick={() => {
                      setActive(tab.id);
                      setSidebar(false);
                    }}
                  >
                    <span className="ce-file">CE</span>
                    {tab.id}.ce{dirty(tab) && <span className="unsaved-dot" />}
                  </button>
                  <button
                    className="tab-close"
                    aria-label={`${tab.id}.ce 닫기`}
                    disabled={!!busy}
                    onClick={() => closeTab(tab)}
                  >
                    ×
                  </button>
                </div>
              ))}
              {!tabs.length && (
                <span className="tab-placeholder">시작하기</span>
              )}
            </div>
            <button
              className={"icon-button guide-toggle " + (guide ? "pressed" : "")}
              aria-label="문법 가이드"
              aria-pressed={guide}
              onClick={() => setGuide(!guide)}
            >
              ?
            </button>
          </div>
          <div className="toolbar">
            <div className="toolbar-group">
              <button
                id="saveButton"
                disabled={!current || !!busy || !connected}
                onClick={saveCurrent}
              >
                저장 <kbd>⌘/Ctrl S</kbd>
              </button>
              <button
                id="buildButton"
                disabled={!current || !!busy || !connected}
                onClick={() => void operation("build")}
              >
                ◇ 빌드 검사
              </button>
              <button
                id="applyButton"
                className="primary"
                disabled={!current || !!busy || !connected}
                onClick={() =>
                  void operation(loaded.includes(active!) ? "reload" : "load")
                }
              >
                ▷ 서버에 적용
              </button>
              <button
                id="unloadButton"
                disabled={
                  !current || !!busy || !connected || !loaded.includes(active!)
                }
                onClick={() => void operation("unload")}
              >
                해제
              </button>
            </div>
            <span id="moduleStatus" className="module-status">
              {busy ? (
                <>
                  <span className="spinner" />
                  {busy}
                </>
              ) : current ? (
                loaded.includes(current.id) ? (
                  <>
                    <i className="running-dot" />
                    실행 중
                  </>
                ) : (
                  "미실행"
                )
              ) : (
                "선택 없음"
              )}
            </span>
          </div>
          {notice && (
            <div className="notice" role="alert">
              <span>{notice}</span>
              <button
                className="icon-button"
                aria-label="알림 닫기"
                onClick={() => setNotice("")}
              >
                ×
              </button>
            </div>
          )}
          <div
            className={"intelligence-status " + intelligenceStatus.phase}
            id="intelligenceStatus"
            role="status"
          >
            <span title={intelligenceStatus.message}>
              {intelligenceStatus.phase === "ready"
                ? `브라우저 코드 분석 · ${intelligenceStatus.version ?? "API 준비됨"}`
                : intelligenceStatus.message}
              {intelligenceStatus.approximate &&
                " · 동일 버전 SNAPSHOT (서버 빌드와 다를 수 있음)"}
            </span>
            {intelligenceStatus.phase === "error" && connected && (
              <button
                onClick={() => intelligence.start(api.authorization, true)}
              >
                API 다시 준비
              </button>
            )}
          </div>
          {current ? (
            <>
              <div className="breadcrumb">
                <span id="filename">
                  modules / <strong>{current.id}.ce</strong>
                </span>
                <div>
                  <span id="dirtyLabel">
                    {dirty(current) ? "● 저장되지 않음" : "저장됨"}
                  </span>
                  <button
                    className="file-action"
                    disabled={!!busy || !connected}
                    onClick={() => showDialog("rename")}
                  >
                    이름 변경
                  </button>
                  <button
                    className="file-action danger"
                    disabled={!!busy || !connected}
                    onClick={() => showDialog("delete")}
                  >
                    파일 삭제
                  </button>
                  <button
                    title="검색 및 치환 (Ctrl/Cmd F)"
                    aria-label="검색 및 치환"
                    onClick={() =>
                      viewRef.current && openSearchPanel(viewRef.current)
                    }
                  >
                    ⌕
                  </button>
                  <button
                    aria-label="자동 줄바꿈"
                    aria-pressed={wrap}
                    className={wrap ? "pressed" : ""}
                    onClick={() => setWrap(!wrap)}
                  >
                    ↵
                  </button>
                  <button
                    title="로컬 사본 다운로드"
                    aria-label="로컬 사본 다운로드"
                    onClick={download}
                  >
                    ↓
                  </button>
                  <button
                    title="서버 파일 다시 열기"
                    aria-label="서버 파일 다시 열기"
                    disabled={!!busy || !connected}
                    onClick={reloadFile}
                  >
                    ↻
                  </button>
                </div>
              </div>
              {current.conflict && (
                <div className="conflict">
                  서버 파일과 충돌했습니다.{" "}
                  <button onClick={download}>로컬 사본 다운로드</button>
                  <button disabled={!!busy} onClick={reloadFile}>
                    서버 파일 다시 열기
                  </button>
                </div>
              )}
              <Editor
                intelligence={intelligence}
                document={current}
                locked={!!busy}
                wrap={wrap}
                onChange={onEdit}
                onPosition={(line, column) => setPosition({ line, column })}
                onSave={saveCurrent}
                onBuild={() => void operation("build")}
                onView={(view) => {
                  viewRef.current = view;
                }}
              />
            </>
          ) : (
            <Welcome
              disabled={!connected || !!busy}
              onCreate={() => showDialog("create")}
            />
          )}
          <OutputPanel
            panel={panel}
            setPanel={setPanel}
            current={current}
            logs={logs}
            onClear={() => setLogs([])}
            onJump={jump}
          />
        </main>
        {guide && (
          <LanguageGuide
            disabled={!current || !!busy}
            onClose={() => setGuide(false)}
            onInsert={insertTemplate}
          />
        )}
      </div>
      <footer className="statusbar">
        <span>
          <i className={connected ? "running-dot" : "offline-dot"} />{" "}
          {connected ? "LOCAL" : "OFFLINE"}
          <span className="status-divider">/</span>
          {changed ? `${changed}개 수정됨` : "모든 변경 저장됨"}
        </span>
        <span>
          <span id="cursor">
            Ln {position.line}, Col {position.column}
          </span>
          <span className="status-detail">
            공백: 4<span className="status-divider">·</span>UTF-8
            <span className="status-divider">·</span>Code Engine
          </span>
        </span>
      </footer>
      {dialog && (
        <Dialog
          title={
            dialog === "connect"
              ? "Studio에 연결"
              : dialog === "create"
                ? "새 .ce 파일 만들기"
                : dialog === "rename"
                  ? "파일 이름 변경"
                  : "파일 삭제"
          }
          onClose={() => {
            if (!busy) {
              setDialog(null);
              setInput("");
            }
          }}
        >
          <form onSubmit={submitDialog}>
            <p>
              {dialog === "connect" ? (
                <>
                  서버 콘솔에서 <code>codeengine webide</code>를 실행하고
                  <br />
                  세션 링크 또는 토큰을 입력하세요.
                </>
              ) : dialog === "create" ? (
                "하나의 파일에서 이벤트, 명령, 작업을 함께 작성하세요."
              ) : dialog === "rename" ? (
                <>
                  {current?.id}.ce의 파일명과 <code>module</code> 선언을 함께
                  변경합니다.{" "}
                  {dirty(current!) && "먼저 변경 내용을 저장하세요."}{" "}
                  {loaded.includes(current!.id) &&
                    "먼저 실행 중인 모듈을 해제하세요."}
                </>
              ) : (
                <>
                  {current?.id}.ce를 서버에서 영구 삭제합니다. 저장하지 않은
                  편집 내용도 사라집니다.{" "}
                  {loaded.includes(current!.id) &&
                    "먼저 실행 중인 모듈을 해제하세요."}
                </>
              )}
            </p>
            {dialog !== "delete" && (
              <>
                <label htmlFor="dialogInput">
                  {dialog === "connect"
                    ? "세션 링크 / 토큰"
                    : dialog === "rename"
                      ? "새 파일명 (.ce 제외)"
                      : "파일명 (.ce 제외)"}
                </label>
                <input
                  id="dialogInput"
                  autoFocus
                  type={dialog === "connect" ? "password" : "text"}
                  autoComplete="off"
                  spellCheck={false}
                  required
                  disabled={!!busy}
                  pattern={
                    dialog === "create" || dialog === "rename"
                      ? "[a-z][a-z0-9_]{0,47}"
                      : undefined
                  }
                  maxLength={
                    dialog === "create" || dialog === "rename" ? 48 : undefined
                  }
                  placeholder={dialog === "create" ? "welcome" : undefined}
                  value={input}
                  onChange={(event) => setInput(event.target.value)}
                />
              </>
            )}
            {(dialog === "create" || dialog === "rename") && (
              <small>영문 소문자로 시작 · 숫자와 밑줄 허용 · 최대 48자</small>
            )}
            {dialogError && (
              <p className="dialog-error" role="alert">
                {dialogError}
              </p>
            )}
            <div className="dialog-actions">
              <button
                type="button"
                disabled={!!busy}
                onClick={() => {
                  setDialog(null);
                  setInput("");
                }}
              >
                취소
              </button>
              <button
                className={dialog === "delete" ? "destructive" : "primary"}
                disabled={
                  !!busy ||
                  ((dialog === "rename" || dialog === "delete") &&
                    !!current &&
                    loaded.includes(current.id)) ||
                  (dialog === "rename" && !!current && dirty(current))
                }
                type="submit"
              >
                {busy
                  ? "처리 중…"
                  : dialog === "connect"
                    ? "연결"
                    : dialog === "create"
                      ? "만들기"
                      : dialog === "rename"
                        ? "이름 변경"
                        : "영구 삭제"}
              </button>
            </div>
          </form>
        </Dialog>
      )}
    </div>
  );
}
