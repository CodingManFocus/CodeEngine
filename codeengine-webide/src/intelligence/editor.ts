import { StateEffect, StateField, type Extension } from "@codemirror/state";
import {
  EditorView,
  ViewPlugin,
  hoverTooltip,
  showTooltip,
  keymap,
  type Tooltip,
  type ViewUpdate,
} from "@codemirror/view";
import { linter, forceLinting } from "@codemirror/lint";
import { ceLanguage } from "../language";
import { toDiagnostics, type Problem } from "../diagnostics";
import type { IntelligenceClient } from "./client";

const signatureEffect = StateEffect.define<Tooltip | null>();
const refreshDiagnostics = StateEffect.define<null>();
export function refreshIntelligenceDiagnostics(view: EditorView) {
  view.dispatch({ effects: refreshDiagnostics.of(null) });
  forceLinting(view);
}
const signatureField = StateField.define<Tooltip | null>({
  create: () => null,
  update(value, transaction) {
    if (transaction.docChanged || transaction.selection) value = null;
    for (const effect of transaction.effects)
      if (effect.is(signatureEffect)) value = effect.value;
    return value;
  },
  provide: (field) => showTooltip.from(field),
});
function tooltip(position: number, text: string): Tooltip {
  return {
    pos: position,
    above: true,
    create() {
      const dom = document.createElement("div");
      dom.className = "ce-api-tooltip";
      dom.textContent = text;
      return { dom };
    },
  };
}
export function intelligenceExtension(
  client: IntelligenceClient,
  problems: () => Problem[],
): Extension {
  async function signature(view: EditorView) {
    const state = view.state;
    const position = state.selection.main.head;
    const result = await client.query(
      "signature",
      state.doc.toString(),
      position,
    );
    if (view.state !== state || !view.dom.isConnected) return;
    view.dispatch({
      effects: signatureEffect.of(
        result ? tooltip(position, result.text) : null,
      ),
    });
  }
  return [
    ceLanguage.data.of({
      autocomplete: async (
        context: import("@codemirror/autocomplete").CompletionContext,
      ) => {
        const result = await client.query(
          "complete",
          context.state.doc.toString(),
          context.pos,
        );
        if (context.aborted) return null;
        return result;
      },
    }),
    hoverTooltip(async (view, position) => {
      const result = await client.query(
        "hover",
        view.state.doc.toString(),
        position,
      );
      return result
        ? { ...tooltip(result.from, result.text), end: result.to }
        : null;
    }),
    linter(
      async (view) => {
        const state = view.state;
        const result = await client.query("diagnostics", state.doc.toString());
        const compiled = toDiagnostics(state.doc, problems());
        return [
          ...compiled,
          ...(result ?? []).map((item) => ({
            ...item,
            source: "브라우저 분석",
          })),
        ];
      },
      {
        delay: 500,
        needsRefresh: (update) =>
          update.transactions.some((transaction) =>
            transaction.effects.some((effect) => effect.is(refreshDiagnostics)),
          ),
      },
    ),
    signatureField,
    keymap.of([
      {
        key: "Ctrl-Shift-Space",
        run(view) {
          void signature(view);
          return true;
        },
      },
    ]),
    ViewPlugin.fromClass(
      class {
        private timer?: ReturnType<typeof setTimeout>;
        private dispose: () => void;
        private alive = true;
        constructor(private view: EditorView) {
          this.dispose = client.subscribe(() => {
            // A status event may arrive while the view is being constructed.
            queueMicrotask(() => {
              if (this.alive) refreshIntelligenceDiagnostics(view);
            });
          });
        }
        update(update: ViewUpdate) {
          if (!update.docChanged && !update.selectionSet) return;
          clearTimeout(this.timer);
          this.timer = setTimeout(() => {
            void signature(this.view);
          }, 180);
        }
        destroy() {
          this.alive = false;
          clearTimeout(this.timer);
          this.dispose();
        }
      },
    ),
  ];
}
