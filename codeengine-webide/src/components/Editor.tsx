import { useLayoutEffect, useRef } from "react";
import { Compartment, EditorState, type Extension } from "@codemirror/state";
import {
  EditorView,
  keymap,
  lineNumbers,
  highlightActiveLine,
  highlightActiveLineGutter,
  drawSelection,
  rectangularSelection,
  crosshairCursor,
  highlightSpecialChars,
} from "@codemirror/view";
import {
  history,
  historyKeymap,
  defaultKeymap,
  indentWithTab,
} from "@codemirror/commands";
import {
  indentOnInput,
  bracketMatching,
  foldGutter,
  foldKeymap,
  indentUnit,
} from "@codemirror/language";
import {
  search,
  searchKeymap,
  highlightSelectionMatches,
} from "@codemirror/search";
import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  completionKeymap,
} from "@codemirror/autocomplete";
import { lintGutter, setDiagnostics } from "@codemirror/lint";
import { codeEngine } from "../language";
import { toDiagnostics, type Problem } from "../diagnostics";

export interface DocumentTab {
  id: string;
  source: string;
  savedSource: string;
  revision: string;
  state?: EditorState;
  scrollTop: number;
  scrollLeft: number;
  problems: Problem[];
  conflict: boolean;
}
const editable = new Compartment();
const wrapping = new Compartment();
const theme = EditorView.theme(
  {
    "&": {
      height: "100%",
      fontSize: "14px",
      color: "#d7dfe9",
      backgroundColor: "#10131a",
    },
    ".cm-scroller": {
      fontFamily: '"SFMono-Regular", Consolas, "Liberation Mono", monospace',
      lineHeight: "1.75",
      overflow: "auto",
    },
    ".cm-content": { padding: "20px 0", caretColor: "#b7a2ff" },
    ".cm-line": { padding: "0 24px 0 12px" },
    ".cm-gutters": {
      backgroundColor: "#10131a",
      color: "#505d70",
      border: "none",
      paddingLeft: "12px",
    },
    ".cm-activeLineGutter": { backgroundColor: "#1a2030", color: "#c3cce0" },
    ".cm-activeLine": { backgroundColor: "#191e29" },
    ".cm-cursor": { borderLeftColor: "#bea9ff" },
    "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection":
      { backgroundColor: "#383452" },
    ".cm-foldGutter": { width: "14px" },
    ".cm-tooltip": {
      backgroundColor: "#202633",
      border: "1px solid #384153",
      color: "#d7dfe9",
    },
    ".cm-tooltip-autocomplete > ul > li[aria-selected]": {
      backgroundColor: "#44395f",
      color: "#fff",
    },
    ".cm-panels": { backgroundColor: "#181e29", color: "#d7dfe9" },
    ".cm-textfield": {
      backgroundColor: "#10131a",
      color: "#d7dfe9",
      border: "1px solid #384153",
    },
    ".cm-button": {
      backgroundImage: "none",
      backgroundColor: "#293142",
      color: "#d7dfe9",
      border: "1px solid #384153",
    },
    ".cm-search": { padding: "8px 12px" },
    ".cm-matchingBracket": {
      backgroundColor: "#49405f",
      outline: "1px solid #8f7abb",
    },
    ".cm-searchMatch": { backgroundColor: "#66502c" },
    ".cm-searchMatch.cm-searchMatch-selected": { backgroundColor: "#986b2b" },
    ".cm-diagnostic": { fontFamily: "inherit" },
  },
  { dark: true },
);
interface Props {
  document: DocumentTab;
  locked: boolean;
  wrap: boolean;
  onChange: (state: EditorState) => void;
  onPosition: (line: number, column: number) => void;
  onSave: () => void;
  onBuild: () => void;
  onView: (view: EditorView | null) => void;
}
export function Editor(props: Props) {
  const container = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const latest = useRef(props);
  latest.current = props;
  useLayoutEffect(() => {
    const document = latest.current.document;
    const extensions: Extension[] = [
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightSpecialChars(),
      history(),
      drawSelection(),
      rectangularSelection(),
      crosshairCursor(),
      highlightActiveLine(),
      highlightSelectionMatches(),
      indentOnInput(),
      bracketMatching(),
      foldGutter(),
      closeBrackets(),
      autocompletion(),
      search({ top: true }),
      lintGutter(),
      codeEngine(),
      theme,
      indentUnit.of("    "),
      EditorState.tabSize.of(4),
      EditorState.allowMultipleSelections.of(true),
      EditorView.cspNonce.of(
        window.document
          .querySelector('meta[name="ce-style-nonce"]')
          ?.getAttribute("content") ?? "",
      ),
      EditorView.contentAttributes.of({
        "aria-label": "Code Engine 소스 편집기",
        spellcheck: "false",
        autocapitalize: "off",
      }),
      editable.of(EditorState.readOnly.of(latest.current.locked)),
      wrapping.of(latest.current.wrap ? EditorView.lineWrapping : []),
      keymap.of([
        {
          key: "Mod-s",
          run: () => {
            latest.current.onSave();
            return true;
          },
        },
        {
          key: "Mod-Enter",
          run: () => {
            latest.current.onBuild();
            return true;
          },
        },
        ...closeBracketsKeymap,
        ...defaultKeymap,
        ...historyKeymap,
        ...searchKeymap,
        ...foldKeymap,
        ...completionKeymap,
        indentWithTab,
      ]),
      EditorView.updateListener.of((update) => {
        if (update.docChanged || update.selectionSet) {
          latest.current.onChange(update.state);
          const position = update.state.selection.main.head,
            line = update.state.doc.lineAt(position);
          latest.current.onPosition(line.number, position - line.from + 1);
        }
      }),
    ];
    const state =
      document.state ??
      EditorState.create({ doc: document.source, extensions });
    const view = new EditorView({ state, parent: container.current! });
    viewRef.current = view;
    latest.current.onView(view);
    view.dispatch({
      effects: [
        editable.reconfigure(EditorState.readOnly.of(latest.current.locked)),
        wrapping.reconfigure(
          latest.current.wrap ? EditorView.lineWrapping : [],
        ),
      ],
    });
    view.scrollDOM.scrollTop = document.scrollTop;
    view.scrollDOM.scrollLeft = document.scrollLeft;
    const line = view.state.doc.lineAt(view.state.selection.main.head);
    latest.current.onPosition(
      line.number,
      view.state.selection.main.head - line.from + 1,
    );
    return () => {
      document.state = view.state;
      document.scrollTop = view.scrollDOM.scrollTop;
      document.scrollLeft = view.scrollDOM.scrollLeft;
      view.destroy();
      viewRef.current = null;
      latest.current.onView(null);
    };
  }, [props.document.id]);
  useLayoutEffect(() => {
    viewRef.current?.dispatch({
      effects: editable.reconfigure(EditorState.readOnly.of(props.locked)),
    });
  }, [props.locked]);
  useLayoutEffect(() => {
    viewRef.current?.dispatch({
      effects: wrapping.reconfigure(props.wrap ? EditorView.lineWrapping : []),
    });
  }, [props.wrap]);
  useLayoutEffect(() => {
    const view = viewRef.current;
    if (view)
      view.dispatch(
        setDiagnostics(
          view.state,
          toDiagnostics(view.state.doc, props.document.problems),
        ),
      );
  }, [props.document.id, props.document.problems]);
  return <div id="editor" className="editor" ref={container} />;
}
