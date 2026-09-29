import type { JavaClass, JavaMember } from "./types";
import {
  scanSource,
  isExcluded,
  tokenBefore,
  type SourceModel,
  type Token,
} from "./ceSource";
import {
  classType,
  fieldType,
  methodType,
  substitute,
  typeText,
  type TypeRef,
  type MethodType,
} from "./javaTypes";

export interface CompletionOption {
  label: string;
  type: string;
  detail: string;
  info?: string;
  apply?: string;
}
export interface CompletionResult {
  from: number;
  options: CompletionOption[];
}
export interface IntelligenceDiagnostic {
  from: number;
  to: number;
  severity: "warning" | "error";
  message: string;
}
interface Import {
  name: string;
  from: number;
  to: number;
  external: boolean;
}
interface Variable {
  name: string;
  type?: TypeRef;
  initializer?: number;
  initializerStart?: number;
  from: number;
  to: number;
  token: number;
  scope: number;
}
interface FunctionDeclaration {
  name: string;
  parameters: { name: string; type: TypeRef }[];
  result?: TypeRef;
  token: number;
}
interface Model extends SourceModel {
  imports: Import[];
  variables: Variable[];
  functions: FunctionDeclaration[];
}
interface Value {
  type: TypeRef;
  static: boolean;
}
interface Member {
  member: JavaMember;
  owner: JavaClass;
  bindings: Map<string, TypeRef>;
}
const PUBLIC = 1,
  STATIC = 8,
  VARARGS = 128,
  INTERFACE = 512,
  SYNTHETIC = 4096;
const defaultPackages = [
  "java.lang",
  "java.util",
  "org.bukkit",
  "org.bukkit.entity",
  "org.bukkit.event",
  "org.bukkit.event.player",
  "org.bukkit.event.block",
];
const primitives = new Set([
  "byte",
  "char",
  "short",
  "int",
  "long",
  "float",
  "double",
  "boolean",
  "void",
]);
const declarationKeywords = new Set([
  "module",
  "state",
  "fn",
  "on",
  "command",
  "every",
  "enable",
  "disable",
  "requires",
  "use",
]);
const nonTypes = new Set([
  "return",
  "throw",
  "new",
  "else",
  "case",
  "break",
  "continue",
  "yield",
  "instanceof",
  "module",
  "use",
  "requires",
  "on",
  "command",
  "fn",
  "every",
  "enable",
  "disable",
]);
const simpleName = (name: string) =>
  name.slice(name.lastIndexOf(".") + 1).replaceAll("$", ".");
const sameType = (a: TypeRef, b: TypeRef): boolean =>
  typeText(a, true) === typeText(b, true) &&
  a.opaque === b.opaque &&
  (a.args ?? []).every((arg, index) =>
    sameType(arg, b.args?.[index] ?? { name: "?" }),
  );

/** A tolerant, JAR-backed editor index. It does not replace javac or claim full Java semantics. */
export class IntelligenceEngine {
  private classes = new Map<string, JavaClass>();
  private sourceModel?: Model;
  private memberCache = new Map<string, Member[]>();
  constructor(classes: JavaClass[]) {
    for (const type of classes)
      if (!this.classes.has(type.name)) this.classes.set(type.name, type);
  }

  complete(source: string, position: number): CompletionResult | null {
    const model = this.model(source);
    if (isExcluded(model, position)) return null;
    const prefix =
      source.slice(0, position).match(/[\p{L}\p{N}\p{M}_$]*$/u)?.[0] ?? "";
    const from = position - prefix.length;
    const before = tokenBefore(model, from);
    const linePrefix = source.slice(
      Math.max(
        source.lastIndexOf(";", from - 1),
        source.lastIndexOf("}", from - 1),
        source.lastIndexOf("{", from - 1),
      ) + 1,
      position,
    );
    const useMatch = linePrefix.match(/^\s*use\s+([\p{L}\p{N}\p{M}_$.]*)$/u);
    if (useMatch) {
      const path = useMatch[1];
      return {
        from: position - path.length,
        options: this.classOptions(path, model, true),
      };
    }
    if (model.tokens[before]?.text === ".") {
      const receiver = this.infer(model, before - 1, position);
      if (!receiver) return null;
      if (receiver.type.array)
        return receiver.static
          ? null
          : {
              from,
              options: "length".startsWith(prefix)
                ? [
                    {
                      label: "length",
                      type: "property",
                      detail: "int · array length",
                    },
                  ]
                : [],
            };
      const grouped = new Map<string, Member[]>();
      for (const member of this.members(receiver.type)) {
        if (
          Boolean(member.member.access & STATIC) !== receiver.static ||
          member.member.name.startsWith("<") ||
          !member.member.name.startsWith(prefix)
        )
          continue;
        const key =
          (member.member.descriptor.startsWith("(") ? "m:" : "f:") +
          member.member.name;
        const entries = grouped.get(key) ?? [];
        entries.push(member);
        grouped.set(key, entries);
      }
      const options = [...grouped.values()].map((entries): CompletionOption => {
        const first = entries[0],
          method = first.member.descriptor.startsWith("(");
        const signatures = entries.map((entry) => this.memberText(entry));
        return {
          label: first.member.name,
          type: method ? "method" : "property",
          detail: method
            ? `${entries.length > 1 ? `${entries.length} overloads · ` : ""}${signatures[0]}`
            : signatures[0],
          info:
            signatures.join("\n") +
            `\n${first.owner.name}` +
            (first.member.deprecated ? "\n@Deprecated" : ""),
        };
      });
      if (receiver.static) {
        for (const type of this.classes.values()) {
          if (
            type.access & PUBLIC &&
            type.name.startsWith(receiver.type.name + "$")
          ) {
            const nested = type.name.slice(receiver.type.name.length + 1);
            if (!nested.includes("$") && nested.startsWith(prefix))
              options.push({
                label: nested,
                type: "class",
                detail: type.name.replaceAll("$", "."),
              });
          }
        }
      }
      return {
        from,
        options: options
          .sort((a, b) => a.label.localeCompare(b.label))
          .slice(0, 250),
      };
    }
    const options: CompletionOption[] = [];
    for (const variable of this.visibleVariables(model, position)) {
      if (!variable.name.startsWith(prefix)) continue;
      const type = this.variableType(model, variable, 0);
      options.push({
        label: variable.name,
        type: "variable",
        detail: type ? typeText(type) : "var",
      });
    }
    for (const fn of model.functions)
      if (fn.name.startsWith(prefix))
        options.push({
          label: fn.name,
          type: "function",
          detail: this.functionText(fn),
        });
    options.push(...this.classOptions(prefix, model, false));
    return { from, options: options.slice(0, 250) };
  }

  hover(
    source: string,
    position: number,
  ): { from: number; to: number; text: string } | null {
    const model = this.model(source);
    if (isExcluded(model, position)) return null;
    let index = model.tokens.findIndex(
      (token) =>
        token.kind === "name" && token.from <= position && token.to >= position,
    );
    if (index < 0) return null;
    const token = model.tokens[index];
    if (model.tokens[index - 1]?.text === ".") {
      const receiver = this.infer(model, index - 2, position);
      if (!receiver) return null;
      const members = this.members(receiver.type).filter(
        (entry) =>
          entry.member.name === token.text &&
          Boolean(entry.member.access & STATIC) === receiver.static,
      );
      if (members.length)
        return {
          from: token.from,
          to: token.to,
          text: members
            .map(
              (entry) =>
                this.memberText(entry) +
                `\n${entry.owner.name}` +
                (entry.member.deprecated ? "\n@Deprecated" : ""),
            )
            .join("\n\n"),
        };
    }
    const variable = this.visibleVariables(model, position).find(
      (entry) => entry.name === token.text,
    );
    if (variable) {
      const type = this.variableType(model, variable, 0);
      return {
        from: token.from,
        to: token.to,
        text: `${type ? typeText(type, true) : "var (type not inferred)"} ${token.text}`,
      };
    }
    const fn = model.functions.find((entry) => entry.name === token.text);
    if (fn)
      return { from: token.from, to: token.to, text: this.functionText(fn) };
    const type = this.resolveType(token.text, model);
    const javaClass = type && !type.opaque && this.classes.get(type.name);
    return javaClass
      ? {
          from: token.from,
          to: token.to,
          text: `${javaClass.access & INTERFACE ? "interface" : "class"} ${javaClass.name.replaceAll("$", ".")}${javaClass.deprecated ? "\n@Deprecated" : ""}`,
        }
      : null;
  }

  signature(source: string, position: number): { text: string } | null {
    const model = this.model(source);
    if (isExcluded(model, position)) return null;
    const end = tokenBefore(model, position);
    for (let open = end; open >= 0; open--) {
      if (
        model.tokens[open].text !== "(" ||
        (model.pairs.get(open) ?? Infinity) <= end
      )
        continue;
      const name = model.tokens[open - 1];
      if (name?.kind !== "name") continue;
      const argument = this.split(model, open + 1, end + 1).length;
      if (model.tokens[open - 2]?.text === ".") {
        const receiver = this.infer(model, open - 3, position);
        if (!receiver) return null;
        const methods = this.members(receiver.type).filter(
          (entry) =>
            entry.member.name === name.text &&
            entry.member.descriptor.startsWith("(") &&
            Boolean(entry.member.access & STATIC) === receiver.static,
        );
        if (methods.length)
          return {
            text:
              `Argument ${Math.max(1, argument)}\n` +
              methods.map((entry) => this.memberText(entry)).join("\n"),
          };
      } else {
        const fn = model.functions.find((entry) => entry.name === name.text);
        if (fn)
          return {
            text: `Argument ${Math.max(1, argument)}\n${this.functionText(fn)}`,
          };
      }
    }
    return null;
  }

  diagnostics(source: string): IntelligenceDiagnostic[] {
    const model = this.model(source),
      diagnostics: IntelligenceDiagnostic[] = [];
    for (const imported of model.imports) {
      if (!imported.external && !this.lookupClass(imported.name))
        diagnostics.push({
          from: imported.from,
          to: imported.to,
          severity: "warning",
          message: `Type '${imported.name}' is unavailable in the downloaded API index. The server compiler is authoritative.`,
        });
    }
    for (let index = 2; index < model.tokens.length; index++) {
      const token = model.tokens[index];
      if (
        token.kind !== "name" ||
        model.tokens[index - 1].text !== "." ||
        token.scope === 0 ||
        token.text === "class"
      )
        continue;
      const receiver = this.infer(model, index - 2, token.from);
      if (!receiver || !this.completeHierarchy(receiver.type)) continue;
      if (receiver.type.array && token.text === "length") continue;
      const matching = this.members(receiver.type).some(
        (entry) =>
          entry.member.name === token.text &&
          (!receiver.static || Boolean(entry.member.access & STATIC)),
      );
      if (
        !matching &&
        !(
          receiver.static &&
          this.lookupClass(receiver.type.name + "$" + token.text)
        )
      )
        diagnostics.push({
          from: token.from,
          to: token.to,
          severity: "warning",
          message: `No ${receiver.static ? "static " : ""}public member '${token.text}' in indexed type ${typeText(receiver.type)}.`,
        });
    }
    return diagnostics.slice(0, 100);
  }

  private lookupClass(name: string): JavaClass | undefined {
    if (this.classes.has(name)) return this.classes.get(name);
    let candidate = name;
    for (
      let index = candidate.lastIndexOf(".");
      index >= 0;
      index = candidate.lastIndexOf(".", index - 1)
    ) {
      candidate = candidate.slice(0, index) + "$" + candidate.slice(index + 1);
      if (this.classes.has(candidate)) return this.classes.get(candidate);
    }
    return undefined;
  }
  private resolveType(text: string, model: Model): TypeRef | undefined {
    text = text
      .trim()
      .replace(/^final\s+/, "")
      .replace(/\.\.\.$/, "[]");
    let array = 0;
    while (text.endsWith("[]")) {
      array++;
      text = text.slice(0, -2);
    }
    const generic = text.indexOf("<");
    let args: TypeRef[] | undefined;
    if (generic >= 0) {
      if (!text.endsWith(">")) return undefined;
      const inner = text.slice(generic + 1, -1);
      const pieces: string[] = [];
      let start = 0,
        depth = 0;
      for (let i = 0; i <= inner.length; i++) {
        if (inner[i] === "<") depth++;
        if (inner[i] === ">") depth--;
        if (i === inner.length || (inner[i] === "," && depth === 0)) {
          pieces.push(inner.slice(start, i));
          start = i + 1;
        }
      }
      args = pieces.map(
        (part) => this.resolveType(part, model) ?? { name: "?" },
      );
      text = text.slice(0, generic);
    }
    if (!text || nonTypes.has(text) || text === "var") return undefined;
    let name: string | undefined;
    let opaque = false;
    if (primitives.has(text)) name = text;
    else if (text.includes(".")) {
      const external = model.imports.find(
        (entry) => entry.external && entry.name === text,
      );
      name = external ? external.name : this.lookupClass(text)?.name;
      opaque = Boolean(external);
    } else {
      const explicit = model.imports.filter(
        (entry) => simpleName(entry.name) === text,
      );
      if (explicit.length === 1) {
        name = explicit[0].external
          ? explicit[0].name
          : (this.lookupClass(explicit[0].name)?.name ?? explicit[0].name);
        opaque = explicit[0].external;
      } else if (explicit.length > 1) return undefined;
      else if (text === "Component")
        name = "net.kyori.adventure.text.Component";
      else {
        const candidates = defaultPackages
          .map((pkg) => this.classes.get(pkg + "." + text))
          .filter((entry): entry is JavaClass => Boolean(entry));
        if (candidates.length === 1) name = candidates[0].name;
        else if (
          !candidates.length &&
          [
            "String",
            "Object",
            "Boolean",
            "Integer",
            "Long",
            "Double",
            "Float",
            "Short",
            "Byte",
            "Character",
            "Void",
          ].includes(text)
        )
          name = "java.lang." + text;
      }
    }
    return name
      ? {
          name,
          ...(opaque ? { opaque: true } : {}),
          ...(args?.length ? { args } : {}),
          ...(array ? { array } : {}),
        }
      : undefined;
  }
  private classOptions(
    prefix: string,
    model: Model,
    qualified: boolean,
  ): CompletionOption[] {
    const options: CompletionOption[] = [];
    for (const type of this.classes.values()) {
      if (
        !(type.access & PUBLIC) ||
        type.access & SYNTHETIC ||
        /\$\d/.test(type.name)
      )
        continue;
      if (
        model.imports.some(
          (entry) =>
            entry.external &&
            entry.name.replaceAll("$", ".") === type.name.replaceAll("$", "."),
        )
      )
        continue;
      const full = type.name.replaceAll("$", "."),
        simple = simpleName(type.name);
      const label = qualified ? full : simple;
      if (!label.startsWith(prefix)) continue;
      const inScope = this.resolveType(simple, model)?.name === type.name;
      options.push({
        label,
        type: type.access & INTERFACE ? "interface" : "class",
        detail: full,
        ...(qualified || inScope ? {} : { apply: full }),
        info:
          (type.deprecated ? "@Deprecated\n" : "") +
          (qualified || inScope
            ? full
            : "Inserts the fully qualified name; no server analysis is required."),
      });
      if (options.length >= 250) break;
    }
    return options.sort((a, b) => a.label.localeCompare(b.label));
  }
  private members(type: TypeRef): Member[] {
    if (type.array || type.opaque) return [];
    const key = JSON.stringify(type);
    const cached = this.memberCache.get(key);
    if (cached) return cached;
    const result: Member[] = [],
      seen = new Set<string>(),
      signatures = new Set<string>();
    const visit = (current: TypeRef, root: boolean) => {
      if (seen.has(current.name)) return;
      seen.add(current.name);
      const owner = this.classes.get(current.name);
      if (!owner) return;
      const generic = classType(owner.signature),
        bindings = new Map<string, TypeRef>();
      generic?.variables.forEach((name, index) => {
        if (current.args?.[index]) bindings.set(name, current.args[index]);
      });
      for (const member of [...owner.fields, ...owner.methods]) {
        if (
          !(member.access & PUBLIC) ||
          member.access & SYNTHETIC ||
          (!root &&
            owner.access & INTERFACE &&
            member.access & STATIC &&
            member.descriptor.startsWith("("))
        )
          continue;
        const method = member.descriptor.startsWith("("),
          signature =
            member.name +
            (method
              ? member.descriptor.slice(0, member.descriptor.indexOf(")") + 1)
              : ":field");
        if (signatures.has(signature)) continue;
        signatures.add(signature);
        result.push({ owner, member, bindings });
      }
      const parents =
        generic?.parents ??
        [owner.superName, ...owner.interfaces]
          .filter((name): name is string => Boolean(name))
          .map((name) => ({ name }));
      parents.forEach((parent) => visit(substitute(parent, bindings), false));
    };
    visit(type, true);
    if (this.memberCache.size >= 2048) this.memberCache.clear();
    this.memberCache.set(key, result);
    return result;
  }
  private memberMethod(entry: Member): MethodType {
    const signature = methodType(
      entry.member.descriptor,
      entry.member.signature,
    );
    return {
      ...signature,
      parameters: signature.parameters.map((type) =>
        substitute(type, entry.bindings),
      ),
      result: substitute(signature.result, entry.bindings),
    };
  }
  private memberText(entry: Member): string {
    const member = entry.member,
      prefix = member.access & STATIC ? "static " : "";
    if (!member.descriptor.startsWith("("))
      return `${prefix}${typeText(substitute(fieldType(member.descriptor, member.signature), entry.bindings))} ${member.name}`;
    const type = this.memberMethod(entry);
    const parameters = type.parameters.map((parameter, index) => {
      let text = typeText(parameter);
      if (member.access & VARARGS && index === type.parameters.length - 1)
        text = text.replace(/\[\]$/, "...");
      return text + " " + (member.parameterNames?.[index] ?? `arg${index + 1}`);
    });
    return `${prefix}${type.variables.length ? `<${type.variables.join(", ")}> ` : ""}${typeText(type.result)} ${member.name}(${parameters.join(", ")})`;
  }
  private functionText(fn: FunctionDeclaration): string {
    return `fn ${fn.name}(${fn.parameters.map((parameter) => typeText(parameter.type) + " " + parameter.name).join(", ")}) -> ${fn.result ? typeText(fn.result) : "?"}`;
  }
  private completeHierarchy(type: TypeRef): boolean {
    if (type.array || type.opaque) return false; // Array clone/Object semantics are outside member diagnostics.
    const visited = new Set<string>();
    const visit = (name: string): boolean => {
      if (visited.has(name)) return true;
      visited.add(name);
      const item = this.classes.get(name);
      if (!item) return false;
      return [item.superName, ...item.interfaces]
        .filter((parent): parent is string => Boolean(parent))
        .every(visit);
    };
    return visit(type.name);
  }
  private model(source: string): Model {
    if (this.sourceModel?.source === source) return this.sourceModel;
    const model: Model = {
      ...scanSource(source),
      imports: [],
      variables: [],
      functions: [],
    };
    const tokens = model.tokens;
    // Imports must be collected before resolving any declaration type.
    for (let index = 0; index < tokens.length; index++) {
      if (tokens[index].scope !== 0 || tokens[index].text !== "use") continue;
      let end = index + 1;
      while (
        end < tokens.length &&
        (tokens[end].kind === "name" || tokens[end].text === ".") &&
        tokens[end].text !== "from"
      )
        end++;
      if (end > index + 1)
        model.imports.push({
          name: tokens
            .slice(index + 1, end)
            .map((token) => token.text)
            .join(""),
          from: tokens[index + 1].from,
          to: tokens[end - 1].to,
          external: tokens[end]?.text === "from",
        });
    }
    model.variables.push({
      name: "ctx",
      type: { name: "kr.codenamemc.codeengine.api.ModuleContext" },
      from: 0,
      to: source.length + 1,
      token: -1,
      scope: 0,
    });
    for (let index = 0; index < tokens.length; index++) {
      if (tokens[index].scope !== 0) continue;
      const kind = tokens[index].text;
      if (!declarationKeywords.has(kind)) continue;
      if (kind === "state") {
        this.declaration(model, index + 1, true);
        continue;
      }
      if (!["on", "command", "fn"].includes(kind)) continue;
      let open = index + 1;
      while (
        open < tokens.length &&
        tokens[open].text !== "{" &&
        tokens[open].text !== ";"
      )
        open++;
      if (tokens[open]?.text !== "{") continue;
      const bodyScope = model.scopes.findIndex((scope) => scope.open === open);
      if (bodyScope < 0) continue;
      const scope = model.scopes[bodyScope];
      const add = (name: string, type: TypeRef, token: number) =>
        model.variables.push({
          name,
          type,
          token,
          from: scope.from,
          to: scope.to,
          scope: bodyScope,
        });
      if (kind === "command") {
        add("sender", { name: "org.bukkit.command.CommandSender" }, -1);
        add("command", { name: "org.bukkit.command.Command" }, -1);
        add("label", { name: "java.lang.String" }, -1);
        add("args", { name: "java.lang.String", array: 1 }, -1);
      } else if (kind === "on") {
        let variable = index + 2;
        while (variable < open && tokens[variable].text === ".") variable += 2;
        const type = this.resolveType(
          tokens
            .slice(index + 1, variable)
            .map((token) => token.text)
            .join(""),
          model,
        );
        if (type && tokens[variable]?.kind === "name")
          add(tokens[variable].text, type, variable);
      } else {
        const left = index + 2,
          right = model.pairs.get(left);
        if (tokens[left]?.text !== "(" || right === undefined || right >= open)
          continue;
        const fn: FunctionDeclaration = {
          name: tokens[index + 1].text,
          token: index + 1,
          parameters: [],
          result: this.resolveType(
            tokens
              .slice(right + 2, open)
              .map((token) => token.text)
              .join(""),
            model,
          ),
        };
        for (const [start, end] of this.split(model, left + 1, right)) {
          const parsed = this.readDeclaration(model, start, end);
          if (parsed?.type) {
            fn.parameters.push({
              name: tokens[parsed.name].text,
              type: parsed.type,
            });
            add(tokens[parsed.name].text, parsed.type, parsed.name);
          }
        }
        model.functions.push(fn);
      }
      index = open;
    }
    // Local declarations are collected lexically. Unknown expressions stay unknown.
    for (let index = 0; index < tokens.length; index++) {
      if (tokens[index].scope === 0 || tokens[index].kind !== "name") continue;
      const previous = tokens[index - 1]?.text;
      if (!["{", "}", ";", "(", ","].includes(previous)) continue;
      this.declaration(model, index, false);
    }
    this.sourceModel = model;
    return model;
  }
  private readDeclaration(
    model: Model,
    start: number,
    limit = model.tokens.length,
  ): { name: number; type?: TypeRef; varType: boolean } | null {
    const tokens = model.tokens;
    if (tokens[start]?.text === "final") start++;
    if (tokens[start]?.kind !== "name" || nonTypes.has(tokens[start].text))
      return null;
    const begin = start;
    start++;
    while (
      start + 1 < limit &&
      tokens[start].text === "." &&
      tokens[start + 1].kind === "name"
    )
      start += 2;
    if (tokens[start]?.text === "<") {
      let depth = 1;
      start++;
      while (start < limit && depth) {
        if (tokens[start].text === "<") depth++;
        if (tokens[start].text === ">") depth--;
        start++;
      }
      if (depth) return null;
    }
    while (tokens[start]?.text === "[" && tokens[start + 1]?.text === "]")
      start += 2;
    if (
      tokens[start]?.text === "." &&
      tokens[start + 1]?.text === "." &&
      tokens[start + 2]?.text === "."
    )
      start += 3;
    if (start >= limit || tokens[start]?.kind !== "name") return null;
    const text = tokens
      .slice(begin, start)
      .map((token) => token.text)
      .join("");
    const varType = text === "var",
      type = this.resolveType(text, model);
    if (!varType && !type) return null;
    return { name: start, type, varType };
  }
  private declaration(model: Model, index: number, state: boolean) {
    const parsed = this.readDeclaration(model, index);
    if (!parsed) return;
    const tokens = model.tokens,
      name = parsed.name;
    if (!["=", ";", ",", ":", ")", "["].includes(tokens[name + 1]?.text))
      return;
    // Parameters of calls/lambdas are not declarations; only explicit for/catch headers are admitted.
    const previous = tokens[index - 1]?.text;
    let range = model.scopes[tokens[index].scope];
    if (!state && previous === "(") {
      if (!["for", "catch"].includes(tokens[index - 2]?.text)) return;
      const end = model.pairs.get(index - 1);
      if (end === undefined) return;
      const bodyScope = model.scopes.find((scope) => scope.open === end + 1);
      if (!bodyScope) return; // Unbraced loop scopes require a full statement parser.
      range = { ...bodyScope, from: tokens[name].to };
    }
    let after = name + 1,
      array = 0;
    while (tokens[after]?.text === "[" && tokens[after + 1]?.text === "]") {
      array++;
      after += 2;
    }
    const type = parsed.type
      ? { ...parsed.type, array: (parsed.type.array ?? 0) + array }
      : undefined;
    let initializer: number | undefined;
    if (tokens[after]?.text === "=") {
      let end = after + 1;
      while (
        end < tokens.length &&
        ![";", ",", "}"].includes(tokens[end].text)
      ) {
        if (["(", "[", "{"].includes(tokens[end].text)) {
          const close = model.pairs.get(end);
          if (close === undefined) break;
          end = close;
        }
        end++;
      }
      if (end > after + 1) initializer = end - 1;
    }
    if (!model.variables.some((variable) => variable.token === name))
      model.variables.push({
        name: tokens[name].text,
        type,
        initializer,
        initializerStart: initializer === undefined ? undefined : after + 1,
        token: name,
        scope: state ? 0 : tokens[index].scope,
        from: state ? 0 : tokens[name].to,
        to: state ? model.source.length + 1 : range.to,
      });
  }
  private visibleVariables(model: Model, position: number): Variable[] {
    const result = new Map<string, Variable>();
    for (const variable of model.variables) {
      if (variable.from > position || variable.to <= position) continue;
      // A local's containing block must contain the position; sibling blocks never leak.
      const scope = model.scopes[variable.scope];
      if (
        variable.scope !== 0 &&
        (scope.from > position || scope.to <= position)
      )
        continue;
      const current = result.get(variable.name);
      if (
        !current ||
        variable.scope > current.scope ||
        (variable.scope === current.scope && variable.from > current.from)
      )
        result.set(variable.name, variable);
    }
    return [...result.values()];
  }
  private variableType(
    model: Model,
    variable: Variable,
    depth: number,
  ): TypeRef | undefined {
    if (variable.type) return variable.type;
    if (variable.initializer === undefined || depth > 20) return undefined;
    return this.inferExpression(
      model,
      variable.initializerStart ?? variable.initializer,
      variable.initializer + 1,
      model.tokens[variable.token].from,
      depth + 1,
    )?.type;
  }
  private split(model: Model, start: number, end: number): [number, number][] {
    if (start >= end) return [];
    const result: [number, number][] = [];
    let begin = start,
      generic = 0;
    for (let index = start; index < end; index++) {
      const token = model.tokens[index].text;
      if (["(", "[", "{"].includes(token)) {
        const close = model.pairs.get(index);
        if (close !== undefined) {
          index = close;
          continue;
        }
      }
      if (token === "<") generic++;
      if (token === ">" && generic) generic--;
      if (token === "," && generic === 0) {
        result.push([begin, index]);
        begin = index + 1;
      }
    }
    result.push([begin, end]);
    return result;
  }
  private infer(
    model: Model,
    end: number,
    position: number,
    depth = 0,
  ): Value | undefined {
    if (depth > 24 || end < 0) return undefined;
    const tokens = model.tokens,
      token = tokens[end];
    if (!token) return undefined;
    const value = (type: TypeRef): Value => ({ type, static: false });
    if (token.kind === "string")
      return value({
        name: token.text.startsWith("'") ? "char" : "java.lang.String",
      });
    if (token.kind === "number")
      return value({
        name: /[lL]$/.test(token.text)
          ? "long"
          : /^0[xXbB]/.test(token.text)
            ? "int"
            : /[fF]$/.test(token.text)
              ? "float"
              : /[.eEdD]/.test(token.text)
                ? "double"
                : "int",
      });
    if (["true", "false"].includes(token.text))
      return value({ name: "boolean" });
    if (token.text === "null") return value({ name: "null" });
    if (token.text === "]") {
      const open = model.pairs.get(end);
      if (open === undefined) return undefined;
      const base = this.infer(model, open - 1, position, depth + 1);
      return base && (base.type.array ?? 0) > 0
        ? value({ ...base.type, array: base.type.array! - 1 })
        : undefined;
    }
    if (token.text === ")") {
      const open = model.pairs.get(end);
      if (open === undefined) return undefined;
      // new Type(...), including qualified and parameterized constructors.
      for (
        let cursor = open - 1, budget = 100;
        cursor >= 0 && budget-- > 0;
        cursor--
      ) {
        if (tokens[cursor].text === "new") {
          const type = this.resolveType(
            tokens
              .slice(cursor + 1, open)
              .map((part) => part.text)
              .join(""),
            model,
          );
          if (type) return value(type);
          break;
        }
        if ([";", "{", "}", "=", "(", ")"].includes(tokens[cursor].text)) break;
      }
      const name = tokens[open - 1];
      if (name?.kind === "name") {
        const ranges = this.split(model, open + 1, end);
        const arguments_ = ranges.map(([start, stop]) =>
          start < stop
            ? this.inferExpression(model, start, stop, position, depth + 1)
            : undefined,
        );
        if (tokens[open - 2]?.text === ".") {
          const receiver = this.infer(model, open - 3, position, depth + 1);
          if (!receiver) return undefined;
          const candidates = this.members(receiver.type).filter(
            (entry) =>
              entry.member.name === name.text &&
              entry.member.descriptor.startsWith("(") &&
              Boolean(entry.member.access & STATIC) === receiver.static,
          );
          const results: TypeRef[] = [];
          for (const entry of candidates) {
            const method = this.memberMethod(entry),
              varargs = Boolean(entry.member.access & VARARGS);
            if (
              varargs
                ? arguments_.length < method.parameters.length - 1
                : arguments_.length !== method.parameters.length
            )
              continue;
            let compatible = true;
            const bindings = new Map<string, TypeRef>();
            for (let index = 0; index < arguments_.length; index++) {
              let parameter =
                method.parameters[
                  Math.min(index, method.parameters.length - 1)
                ];
              if (!parameter) {
                compatible = false;
                break;
              }
              if (
                varargs &&
                index >= method.parameters.length - 1 &&
                (parameter.array ?? 0) > 0 &&
                !arguments_[index]?.type.array
              )
                parameter = { ...parameter, array: parameter.array! - 1 };
              const actual = arguments_[index]?.type;
              if (actual && parameter.variable && actual.name !== "null") {
                const dimensions = (actual.array ?? 0) - (parameter.array ?? 0);
                if (dimensions < 0) {
                  compatible = false;
                  break;
                }
                const boxed: Record<string, string> = {
                  boolean: "Boolean",
                  byte: "Byte",
                  short: "Short",
                  char: "Character",
                  int: "Integer",
                  long: "Long",
                  float: "Float",
                  double: "Double",
                };
                const binding = {
                  ...actual,
                  array: dimensions,
                  name:
                    dimensions === 0 && boxed[actual.name]
                      ? "java.lang." + boxed[actual.name]
                      : actual.name,
                };
                const existing = bindings.get(parameter.name);
                // Computing the least upper bound needs full Java inference;
                // never substitute whichever argument happened to come last.
                if (existing && !sameType(existing, binding)) return undefined;
                bindings.set(parameter.name, binding);
              } else if (actual && !this.assignable(actual, parameter)) {
                compatible = false;
                break;
              }
            }
            if (compatible) {
              const result = substitute(method.result, bindings);
              // Unbound method/class type variables cannot safely drive member inference.
              if (
                result.variable ||
                result.name === "?" ||
                result.variance === "-"
              )
                return undefined;
              results.push(result);
            }
          }
          return results.length &&
            results.every((result) => sameType(results[0], result))
            ? value(results[0])
            : undefined;
        }
        const fn = model.functions.find((entry) => entry.name === name.text);
        if (fn?.result) return value(fn.result);
      }
      // Only infer a parenthesized expression if it has no unsupported operators.
      return this.inferExpression(model, open + 1, end, position, depth + 1);
    }
    if (token.kind !== "name") return undefined;
    if (tokens[end - 1]?.text === ".") {
      // First check a fully qualified class name, then instance/static fields.
      let start = end;
      while (
        start >= 2 &&
        tokens[start - 1].text === "." &&
        tokens[start - 2].kind === "name"
      )
        start -= 2;
      const qualified = this.resolveType(
        tokens
          .slice(start, end + 1)
          .map((part) => part.text)
          .join(""),
        model,
      );
      if (qualified) return { type: qualified, static: true };
      const receiver = this.infer(model, end - 2, position, depth + 1);
      if (!receiver) return undefined;
      if (receiver.type.array && token.text === "length")
        return value({ name: "int" });
      const entry = this.members(receiver.type).find(
        (member) =>
          member.member.name === token.text &&
          !member.member.descriptor.startsWith("(") &&
          Boolean(member.member.access & STATIC) === receiver.static,
      );
      if (entry) {
        const type = substitute(
          fieldType(entry.member.descriptor, entry.member.signature),
          entry.bindings,
        );
        return type.variable ? undefined : value(type);
      }
      if (receiver.static) {
        const nested = this.lookupClass(receiver.type.name + "$" + token.text);
        if (nested) return { type: { name: nested.name }, static: true };
      }
      return undefined;
    }
    const variable = this.visibleVariables(model, position).find(
      (entry) => entry.name === token.text,
    );
    if (variable) {
      const type = this.variableType(model, variable, depth + 1);
      return type ? value(type) : undefined;
    }
    const type = this.resolveType(token.text, model);
    return type ? { type, static: true } : undefined;
  }
  private inferExpression(
    model: Model,
    start: number,
    end: number,
    position: number,
    depth: number,
  ): Value | undefined {
    // Avoid claiming the right operand's type for arithmetic, comparisons, conditionals or assignments.
    for (let index = start; index < end; index++) {
      const text = model.tokens[index].text;
      if (["(", "[", "{"].includes(text)) {
        const close = model.pairs.get(index);
        if (close !== undefined) {
          index = close;
          continue;
        }
      }
      if (
        [
          "+",
          "-",
          "*",
          "/",
          "%",
          "?",
          "=",
          "&",
          "|",
          "!",
          "<",
          ">",
          "instanceof",
        ].includes(text)
      )
        return undefined;
    }
    return this.infer(model, end - 1, position, depth + 1);
  }
  private assignable(actual: TypeRef, target: TypeRef): boolean {
    if (
      actual.opaque ||
      target.opaque ||
      target.variable ||
      target.name === "?"
    )
      return true;
    if (actual.name === "null")
      return !primitives.has(target.name) || Boolean(target.array);
    if ((actual.array ?? 0) !== (target.array ?? 0))
      return target.name === "java.lang.Object" && !target.array;
    if (actual.name === target.name) return true;
    const numeric = ["byte", "short", "int", "long", "float", "double"];
    if (numeric.includes(actual.name) && numeric.includes(target.name))
      return numeric.indexOf(actual.name) <= numeric.indexOf(target.name);
    const boxed: Record<string, string> = {
      boolean: "Boolean",
      byte: "Byte",
      short: "Short",
      char: "Character",
      int: "Integer",
      long: "Long",
      float: "Float",
      double: "Double",
    };
    if (boxed[actual.name] && target.name === "java.lang." + boxed[actual.name])
      return true;
    if (target.name === "java.lang.Object" && !primitives.has(actual.name))
      return true;
    // If an ancestry is missing, preserve the candidate instead of inventing a type error.
    const seen = new Set<string>();
    const visit = (name: string): boolean => {
      if (name === target.name) return true;
      if (seen.has(name)) return false;
      seen.add(name);
      const type = this.classes.get(name);
      if (!type) return true;
      return [type.superName, ...type.interfaces]
        .filter((parent): parent is string => Boolean(parent))
        .some(visit);
    };
    return (
      !primitives.has(actual.name) &&
      !primitives.has(target.name) &&
      visit(actual.name)
    );
  }
}
