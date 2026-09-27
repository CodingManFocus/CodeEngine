"use strict";
const byId = id => document.getElementById(id);
const editor = byId("editor");
let token = location.hash.slice(1);
history.replaceState(null, "", location.pathname);
let selectedId = null, revision = null, savedSource = "", loaded = new Set(), locked = false;
function dirty() { return selectedId !== null && editor.value !== savedSource; }
function log(message) {
  const output = byId("output");
  output.textContent = (output.textContent + "\n" + new Date().toLocaleTimeString() + "  " + message).slice(-30000);
  output.parentElement.scrollTop = output.parentElement.scrollHeight;
}
async function api(path, options = {}) {
  const response = await fetch("/api/" + path, { ...options, headers: { Authorization: "Bearer " + token, ...options.headers } });
  const result = await response.json();
  if (!response.ok) {
    if (response.status === 401) byId("connection").textContent = "인증 필요";
    throw new Error(result.error || "요청 실패: " + response.status);
  }
  return result;
}
function refreshEditorState() {
  byId("dirtyLabel").textContent = dirty() ? "● 저장되지 않음" : "";
  byId("lineNumbers").textContent = Array.from({length: editor.value.split("\n").length}, (_, i) => i + 1).join("\n");
  const before = editor.value.slice(0, editor.selectionStart).split("\n");
  byId("cursor").textContent = `Ln ${before.length}, Col ${before.at(-1).length + 1}`;
  for (const id of ["saveButton", "buildButton", "applyButton"]) byId(id).disabled = locked || !selectedId;
  byId("unloadButton").disabled = locked || !selectedId || !loaded.has(selectedId);
  byId("moduleStatus").textContent = !selectedId ? "선택 없음" : loaded.has(selectedId) ? "● 실행 중" : "○ 미실행";
  editor.disabled = locked || !selectedId;
  byId("connectButton").disabled = locked;
  byId("newButton").disabled = locked;
  byId("refreshButton").disabled = locked;
}
async function refreshModules() {
  const state = await api("modules"); loaded = new Set(state.loaded);
  byId("moduleList").replaceChildren();
  for (const id of state.modules) {
    const button = document.createElement("button");
    button.textContent = id + ".ce";
    if (id === selectedId) button.className = "active";
    const dot = document.createElement("span"); dot.className = "dot" + (loaded.has(id) ? " running" : "");
    dot.setAttribute("aria-label", loaded.has(id) ? "실행 중" : "미실행"); button.append(dot);
    button.addEventListener("click", () => run(() => openModule(id)));
    byId("moduleList").append(button);
  }
  byId("connection").textContent = "로컬 세션 연결됨";
  refreshEditorState();
}
async function openModule(id) {
  if (dirty() && !confirm("저장하지 않은 변경을 버리고 모듈을 여시겠습니까?")) return;
  const result = await api("file?id=" + encodeURIComponent(id));
  selectedId = id; revision = result.revision; savedSource = result.source; editor.value = savedSource;
  byId("filename").textContent = id + ".ce";
  await refreshModules();
}
async function save() {
  if (!selectedId) return;
  const result = await api("file?id=" + encodeURIComponent(selectedId), {method:"PUT", headers:{"If-Match":`"${revision}"`, "Content-Type":"text/plain; charset=utf-8"}, body:editor.value});
  revision = result.revision; savedSource = result.source; log("저장 완료: " + selectedId + ".ce"); refreshEditorState();
}
async function operation(action) {
  if (!selectedId) return;
  if (action !== "unload") await save();
  const {job} = await api(`operation?id=${encodeURIComponent(selectedId)}&action=${action}`, {method:"POST"});
  log(action + " 시작: " + selectedId);
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 400));
    const status = await api("job?id=" + encodeURIComponent(job));
    if (status.status === "running") continue;
    log(status.message); await refreshModules(); return;
  }
  throw new Error("작업이 계속 진행 중입니다. 서버 콘솔에서 결과를 확인하세요.");
}
async function run(action) {
  if (locked) return;
  locked = true; refreshEditorState();
  try { await action(); } catch (error) { log("오류: " + error.message); }
  finally { locked = false; refreshEditorState(); }
}
byId("saveButton").onclick = () => run(save);
byId("buildButton").onclick = () => run(() => operation("build"));
byId("applyButton").onclick = () => run(() => operation(loaded.has(selectedId) ? "reload" : "load"));
byId("unloadButton").onclick = () => run(() => operation("unload"));
byId("refreshButton").onclick = () => run(refreshModules);
byId("clearButton").onclick = () => { byId("output").textContent = ""; };
byId("connectButton").onclick = () => { byId("connectDialog").returnValue = "cancel"; byId("connectDialog").showModal(); };
byId("connectDialog").addEventListener("close", () => {
  if (byId("connectDialog").returnValue !== "connect") return;
  const input = byId("sessionInput").value.trim(); token = input.includes("#") ? input.split("#").at(-1) : input;
  byId("sessionInput").value = ""; run(refreshModules);
});
byId("newButton").onclick = () => { byId("newDialog").returnValue = "cancel"; byId("newDialog").showModal(); };
byId("newDialog").addEventListener("close", () => {
  if (byId("newDialog").returnValue !== "create") return;
  run(async () => {
    if (dirty() && !confirm("저장하지 않은 변경을 버리고 새 모듈을 여시겠습니까?")) return;
    const id = byId("moduleInput").value.trim();
    const source = `module ${id};\n\non PlayerJoinEvent event {\n    event.getPlayer().sendMessage(Component.text("Hello!"));\n}\n`;
    await api("file?id=" + encodeURIComponent(id), {method:"PUT", headers:{"If-Match":'"new"', "Content-Type":"text/plain; charset=utf-8"}, body:source});
    savedSource = editor.value; await openModule(id); log("모듈 생성: " + id);
  });
});
editor.addEventListener("input", refreshEditorState);
editor.addEventListener("click", refreshEditorState);
editor.addEventListener("keyup", refreshEditorState);
editor.addEventListener("scroll", () => { byId("lineNumbers").scrollTop = editor.scrollTop; });
editor.addEventListener("keydown", event => {
  if (event.key === "Tab") {
    event.preventDefault(); editor.setRangeText("    ", editor.selectionStart, editor.selectionEnd, "end"); refreshEditorState();
  }
});
document.addEventListener("keydown", event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") { event.preventDefault(); if (selectedId) run(save); }
});
window.addEventListener("beforeunload", event => { if (dirty()) { event.preventDefault(); event.returnValue = ""; } });
if (token) run(refreshModules); else byId("connectDialog").showModal();
