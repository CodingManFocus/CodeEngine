import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { EditorState } from "@codemirror/state";
import { StringStream, ensureSyntaxTree, foldable } from "@codemirror/language";
import { snippet, CompletionContext } from "@codemirror/autocomplete";
import {
  startState,
  token,
  codeEngine,
  ceCompletions,
  templates,
} from "../src/language";
import { parseProblems, toDiagnostics } from "../src/diagnostics";
function scan(source: string) {
  const state = startState(),
    tokens: { text: string; style: string | null }[] = [];
  for (const line of source.split("\n")) {
    const stream = new StringStream(line, 4, 2);
    while (!stream.eol()) {
      stream.start = stream.pos;
      const style = token(stream, state);
      tokens.push({ text: stream.current(), style });
    }
  }
  return { state, tokens };
}
test("all current declarations, modifiers, Java bodies and Unicode identifiers", () => {
  const { tokens, state } = scan(
    'module hello;\nrequires plugin "PlaceholderAPI";\nuse java.util.UUID;\nstate int 수 = 1;\nfn twice(int n) -> int { return n * 2; }\non PlayerJoinEvent event priority HIGH ignoreCancelled { event.setCancelled(true); }\ncommand welcome permission "server.welcome" { return true; }\nevery 20 ticks after 1 ticks {}\nenable {}\ndisable {}',
  );
  for (const text of [
    "module",
    "requires",
    "plugin",
    "use",
    "state",
    "fn",
    "on",
    "priority",
    "ignoreCancelled",
    "command",
    "permission",
    "every",
    "ticks",
    "after",
    "enable",
    "disable",
    "return",
  ])
    assert.ok(
      tokens.some((t) => t.text === text && t.style === "keyword"),
      text,
    );
  assert.ok(tokens.some((t) => t.text === "수" && t.style === "variableName"));
  assert.equal(state.depth, 0);
  assert.equal(state.declaration, "");
});
test("DSL words are ordinary Java identifiers in bodies", () => {
  const { tokens } = scan(
    "module m;\nenable { int ticks = 1; command(); priority++; }",
  );
  assert.equal(tokens.find((t) => t.text === "ticks")?.style, "variableName");
  assert.equal(
    tokens.find((t) => t.text === "command")?.style,
    "variableName.function",
  );
  assert.equal(
    tokens.find((t) => t.text === "priority")?.style,
    "variableName",
  );
});
test("multiline comments, escaped quotes and Java text blocks do not alter brace depth", () => {
  const source =
    'module m;\nenable {\n/* }\n every 2 ticks { */\nString text = "\\\"}";\nString block = """\n} on FakeEvent x {\n\\""" still text\n""";\n}';
  const { tokens, state } = scan(source);
  assert.equal(state.depth, 0);
  assert.equal(state.mode, "code");
  assert.ok(
    tokens.some((t) => t.style === "string" && t.text.includes("FakeEvent")),
  );
});
test("repository examples finish with balanced lexical state", () => {
  for (const name of readdirSync("../examples").filter((n) =>
    n.endsWith(".ce"),
  )) {
    const { state } = scan(readFileSync("../examples/" + name, "utf8"));
    assert.equal(state.depth, 0, name);
    assert.equal(state.mode, "code", name);
  }
});
test("snippet completion only at top level, not in strings/comments or Java blocks", () => {
  for (const [source, expected] of [
    ["module m;\n\non", true],
    ["module m;\nenable {\n on", false],
    ["module m;\n// on", false],
    ['module m;\nenable { String s = "on', false],
  ] as const) {
    const state = EditorState.create({
      doc: source,
      extensions: [codeEngine()],
    });
    ensureSyntaxTree(state, state.doc.length, 1000);
    const result = ceCompletions(
      new CompletionContext(state, state.doc.length, true),
    );
    assert.equal(
      result?.options.some((option) => option.label === "on") ?? false,
      expected,
      source,
    );
  }
});
test("folding ignores braces inside comments and strings", () => {
  const source =
    'module m;\nenable {\n String s = "}";\n /* } */\n if (true) {}\n}\n';
  const state = EditorState.create({ doc: source, extensions: [codeEngine()] });
  ensureSyntaxTree(state, state.doc.length, 1000);
  const line = state.doc.line(2),
    fold = foldable(state, line.from, line.to);
  assert.deepEqual(fold, { from: line.to, to: state.doc.line(6).from });
});
test("snippets cover every supported declaration other than the file header", () => {
  assert.deepEqual(templates.map((x) => x.label).sort(), [
    "command",
    "disable",
    "enable",
    "every",
    "fn",
    "on",
    "requires",
    "state",
    "use",
    "use from",
  ]);
});
test("compiler diagnostics preserve detail and only target the requested module", () => {
  const problems = parseProblems(
    "hello.ce:4: cannot find symbol\n  symbol: missing\nhello.ce:6: another error",
    "hello",
  );
  assert.deepEqual(problems, [
    { line: 4, message: "cannot find symbol\n  symbol: missing" },
    { line: 6, message: "another error" },
  ]);
  assert.deepEqual(parseProblems("line 2: Expected module", "hello"), [
    { line: 2, message: "Expected module" },
  ]);
  assert.deepEqual(parseProblems("other.ce:2: invalid", "hello"), []);
  const state = EditorState.create({ doc: "one\ntwo" });
  assert.equal(
    toDiagnostics(state.doc, [
      { line: 2, message: "error" },
      { line: 900, message: "stale" },
    ]).length,
    1,
  );
});

test("numeric snippet defaults insert usable values", () => {
  for (const [label, expected] of [
    ["every", "every 20 ticks"],
    ["state", "state int count = 0;"],
  ]) {
    const editor = {
      state: EditorState.create(),
      dispatch(transaction: import("@codemirror/state").Transaction) {
        this.state = transaction.state;
      },
    };
    snippet(templates.find((item) => item.label === label)!.code)(
      editor,
      null,
      0,
      0,
    );
    assert.ok(editor.state.doc.toString().includes(expected));
  }
});

test("provider-qualified imports highlight from only in the declaration", () => {
  const { tokens } = scan('module m; use example.Api from "PluginA"; enable { int from = 1; }');
  const matches = tokens.filter((item) => item.text === "from");
  assert.deepEqual(matches.map((item) => item.style), ["keyword", "variableName"]);
});
