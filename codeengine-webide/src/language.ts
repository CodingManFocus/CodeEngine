import {
  StreamLanguage,
  LanguageSupport,
  HighlightStyle,
  syntaxHighlighting,
  foldService,
  matchBrackets,
  syntaxTree,
  type StringStream,
} from "@codemirror/language";
import {
  snippetCompletion,
  type CompletionContext,
} from "@codemirror/autocomplete";
import { tags } from "@lezer/highlight";

const declarations = new Set(
  "module use requires state fn on command every enable disable".split(" "),
);
const modifiers = new Set(
  "priority ignoreCancelled permission ticks after plugin".split(" "),
);
const javaKeywords = new Set(
  "abstract assert break case catch class const continue default do else enum extends final finally for goto if implements import instanceof interface native new package private protected public record return sealed non-sealed static strictfp super switch synchronized this throw throws transient try var void volatile while yield permits".split(
    " ",
  ),
);
const primitives = new Set(
  "boolean byte char double float int long short".split(" "),
);
const constants = new Set("LOWEST LOW NORMAL HIGH HIGHEST MONITOR".split(" "));
export interface LexState {
  mode: "code" | "comment" | "textBlock";
  depth: number;
  declaration: string;
  expectName: string;
}
export function startState(): LexState {
  return { mode: "code", depth: 0, declaration: "", expectName: "" };
}
function quoted(stream: StringStream, quote: string) {
  let escaped = false,
    char;
  while ((char = stream.next()) !== undefined) {
    if (char === quote && !escaped) break;
    if (char === "\\" && !escaped) escaped = true;
    else escaped = false;
  }
}
function textBlock(stream: StringStream, state: LexState) {
  while (!stream.eol()) {
    if (stream.match('"""')) {
      state.mode = "code";
      break;
    }
    if (stream.next() === "\\") stream.next();
  }
}
export function token(stream: StringStream, state: LexState): string | null {
  if (state.mode === "comment") {
    if (stream.skipTo("*/")) {
      stream.match("*/");
      state.mode = "code";
    } else stream.skipToEnd();
    return "comment";
  }
  if (state.mode === "textBlock") {
    textBlock(stream, state);
    return "string";
  }
  if (stream.eatSpace()) return null;
  if (stream.match("//")) {
    stream.skipToEnd();
    return "comment";
  }
  if (stream.match("/*")) {
    state.mode = "comment";
    return token(stream, state);
  }
  if (stream.match('"""')) {
    state.mode = "textBlock";
    textBlock(stream, state);
    return "string";
  }
  if (stream.match('"')) {
    quoted(stream, '"');
    return "string";
  }
  if (stream.match("'")) {
    quoted(stream, "'");
    return "string";
  }
  if (
    stream.match(
      /(?:0[xX][\da-fA-F_]+(?:\.[\da-fA-F_]*)?(?:[pP][+-]?[\d_]+)?|0[bB][01_]+|(?:\d[\d_]*(?:\.[\d_]*)?|\.\d[\d_]*)(?:[eE][+-]?[\d_]+)?)[fFdDlL]?/,
    )
  )
    return "number";
  const word = stream.match(/^[\p{L}_$][\p{L}\p{N}\p{M}_$]*/u);
  if (word) {
    const value = (word as RegExpMatchArray)[0];
    if (state.depth === 0 && !state.declaration && declarations.has(value)) {
      state.declaration = value;
      state.expectName = ["module", "fn", "command", "on"].includes(value)
        ? value
        : "";
      return "keyword";
    }
    if (state.expectName) {
      const expected = state.expectName;
      state.expectName = "";
      return expected === "on"
        ? "typeName"
        : expected === "fn"
          ? "variableName.function"
          : "variableName.definition";
    }
    if (
      state.depth === 0 &&
      modifiers.has(value) &&
      ["on", "every", "command", "requires"].includes(state.declaration)
    )
      return "keyword";
    if (value === "true" || value === "false" || value === "null")
      return "atom";
    if (javaKeywords.has(value)) return "keyword";
    if (primitives.has(value)) return "typeName";
    if (constants.has(value) || /^[A-Z][A-Z_\d]+$/.test(value)) return "atom";
    if (/^[A-Z]/.test(value)) return "typeName";
    if (stream.match(/^\s*\(/, false)) return "variableName.function";
    return "variableName";
  }
  const char = stream.next();
  if (char === "{") state.depth++;
  if (char === "}") {
    state.depth = Math.max(0, state.depth - 1);
    if (!state.depth) state.declaration = "";
  }
  if (char === ";" && state.depth === 0) state.declaration = "";
  if (char && "{}()[]".includes(char)) return "bracket";
  if (char && /[+\-*/%=!<>?:&|^~]/.test(char)) {
    stream.eatWhile(/[+\-*/%=!<>?:&|^~]/);
    return "operator";
  }
  return "punctuation";
}
export const ceLanguage = StreamLanguage.define<LexState>({
  name: "codeengine",
  startState,
  token,
  copyState: (state) => ({ ...state }),
  indent: (state, textAfter, context) =>
    state.mode === "code"
      ? Math.max(0, state.depth - (/^\s*}/.test(textAfter) ? 1 : 0)) *
        context.unit
      : null,
  languageData: {
    commentTokens: { line: "//", block: { open: "/*", close: "*/" } },
    closeBrackets: { brackets: ["(", "[", "{", '"', "'"] },
  },
});
export const templates = [
  { label: "requires", detail: "외부 플러그인 API", code: 'requires plugin "${PlaceholderAPI}";' },
  {
    label: "on",
    detail: "이벤트 처리",
    code: "on ${EventType} event {\n\t${}\n}",
  },
  {
    label: "command",
    detail: "명령 등록",
    code: "command ${name} {\n\t${}\n\treturn true;\n}",
  },
  {
    label: "every",
    detail: "주기 작업",
    code: "every ${1:20} ticks {\n\t${}\n}",
  },
  {
    label: "fn",
    detail: "함수 선언",
    code: "fn ${name}(${}) -> ${void} {\n\t${}\n}",
  },
  {
    label: "state",
    detail: "모듈 상태",
    code: "state ${1:int} ${2:count} = ${3:0};",
  },
  {
    label: "use",
    detail: "타입 가져오기",
    code: "use ${org.bukkit.event.entity.EntityDamageEvent};",
  },
  { label: "enable", detail: "활성화", code: "enable {\n\t${}\n}" },
  { label: "disable", detail: "비활성화", code: "disable {\n\t${}\n}" },
];
export function ceCompletions(context: CompletionContext) {
  const node = syntaxTree(context.state).resolveInner(context.pos, -1);
  if (/string|comment/i.test(node.name)) return null;
  const word = context.matchBefore(/[\p{L}_$][\p{L}\p{N}\p{M}_$]*/u);
  if (!word && !context.explicit) return null;
  // Stream parser keeps declaration completions out of Java bodies and expressions.
  const line = context.state.doc.lineAt(context.pos);
  let depth = 0;
  syntaxTree(context.state).iterate({
    to: line.from,
    enter(node) {
      if (node.name === "bracket")
        for (const char of context.state.sliceDoc(
          node.from,
          Math.min(node.to, line.from),
        )) {
          if (char === "{") depth++;
          else if (char === "}") depth--;
        }
    },
  });
  const topLevel =
    depth === 0 && /^\s*\w*$/.test(line.text.slice(0, context.pos - line.from));
  const options = topLevel
    ? templates.map((item) =>
        snippetCompletion(item.code, {
          label: item.label,
          detail: item.detail,
          type: "keyword",
        }),
      )
    : [];
  const words = topLevel
    ? ["module"]
    : [...javaKeywords, ...primitives, "true", "false", "null"];
  return {
    from: word?.from ?? context.pos,
    options: [
      ...options,
      ...words.map((label) => ({ label, type: "keyword" })),
    ],
    validFor: /^[\p{L}\p{N}\p{M}_$]*$/u,
  };
}
export const ceHighlight = HighlightStyle.define([
  { tag: tags.keyword, color: "#c4a3ff" },
  { tag: tags.typeName, color: "#74d5db" },
  { tag: [tags.string, tags.character], color: "#c2d991" },
  { tag: tags.comment, color: "#718096", fontStyle: "italic" },
  { tag: [tags.number, tags.bool, tags.null, tags.atom], color: "#f1be85" },
  { tag: tags.function(tags.variableName), color: "#8cbbff" },
  { tag: tags.definition(tags.variableName), color: "#e4cb9c" },
  { tag: tags.operator, color: "#a9b8cb" },
  { tag: tags.bracket, color: "#b8c8da" },
]);
export function codeEngine() {
  return new LanguageSupport(ceLanguage, [
    syntaxHighlighting(ceHighlight),
    ceLanguage.data.of({ autocomplete: ceCompletions }),
    foldService.of((state, from, to) => {
      const text = state.sliceDoc(from, to);
      for (let i = 0; i < text.length; i++) {
        if (text[i] !== "{") continue;
        const position = from + i;
        if (
          /string|comment/i.test(
            syntaxTree(state).resolveInner(position + 1, -1).name,
          )
        )
          continue;
        const match = matchBrackets(state, position, 1);
        if (match?.matched && match.end && match.end.from > to)
          return { from: position + 1, to: match.end.from };
      }
      return null;
    }),
  ]);
}
