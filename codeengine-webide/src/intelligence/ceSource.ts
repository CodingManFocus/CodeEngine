/** Tolerant lexical model; Java body compilation remains the server compiler's job. */
export interface Token {
  text: string;
  from: number;
  to: number;
  kind: "name" | "string" | "number" | "symbol";
  scope: number;
}
export interface Scope {
  from: number;
  to: number;
  parent: number;
  open: number;
}
export interface SourceModel {
  source: string;
  tokens: Token[];
  pairs: Map<number, number>;
  scopes: Scope[];
  excluded: { from: number; to: number; closed: boolean }[];
}
const identifierStart = /[\p{L}_$]/u,
  identifierPart = /[\p{L}\p{N}\p{M}_$]/u;
export function scanSource(source: string): SourceModel {
  const tokens: Token[] = [],
    scopes: Scope[] = [
      { from: 0, to: source.length + 1, parent: -1, open: -1 },
    ];
  const excluded: SourceModel["excluded"] = [],
    pairs = new Map<number, number>();
  const delimiters: number[] = [];
  let position = 0,
    scope = 0;
  while (position < source.length) {
    const from = position,
      char = source[position];
    if (/\s/.test(char)) {
      position++;
      continue;
    }
    if (source.startsWith("//", position)) {
      const end = source.indexOf("\n", position + 2);
      position = end < 0 ? source.length : end;
      excluded.push({ from, to: position, closed: end >= 0 });
      continue;
    }
    if (source.startsWith("/*", position)) {
      const end = source.indexOf("*/", position + 2);
      position = end < 0 ? source.length : end + 2;
      excluded.push({ from, to: position, closed: end >= 0 });
      continue;
    }
    let kind: Token["kind"] = "symbol";
    if (char === '"' || char === "'") {
      const quote = source.startsWith('"""', position) ? '"""' : char;
      position += quote.length;
      let closed = false;
      while (position < source.length) {
        if (source[position] === "\\") {
          position = Math.min(source.length, position + 2);
          continue;
        }
        if (source.startsWith(quote, position)) {
          position += quote.length;
          closed = true;
          break;
        }
        position++;
      }
      excluded.push({ from, to: position, closed });
      kind = "string";
    } else if (identifierStart.test(char)) {
      position++;
      while (position < source.length && identifierPart.test(source[position]))
        position++;
      kind = "name";
    } else if (/[0-9]/.test(char)) {
      const match = source
        .slice(position)
        .match(
          /^(?:0[xX][\da-fA-F_]+|0[bB][01_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d[\d_]*)?)[fFdDlL]?/,
        );
      position += match?.[0].length ?? 1;
      kind = "number";
    } else
      position +=
        source.startsWith("->", position) || source.startsWith("::", position)
          ? 2
          : 1;
    const index = tokens.length,
      text = source.slice(from, position);
    tokens.push({ text, from, to: position, kind, scope });
    if (text === "{" || text === "(" || text === "[") {
      delimiters.push(index);
      if (text === "{") {
        scopes.push({
          from: position,
          to: source.length + 1,
          parent: scope,
          open: index,
        });
        scope = scopes.length - 1;
      }
    } else if (text === "}" || text === ")" || text === "]") {
      const expected = text === "}" ? "{" : text === ")" ? "(" : "[";
      if (delimiters.length && tokens[delimiters.at(-1)!].text === expected) {
        const open = delimiters.pop()!;
        pairs.set(open, index);
        pairs.set(index, open);
      }
      if (text === "}" && scope > 0) {
        scopes[scope].to = from;
        scope = scopes[scope].parent;
      }
    }
  }
  return { source, tokens, pairs, scopes, excluded };
}
export function isExcluded(model: SourceModel, position: number): boolean {
  return model.excluded.some(
    (range) =>
      position > range.from &&
      (position < range.to || (!range.closed && position === range.to)),
  );
}
export function tokenBefore(model: SourceModel, position: number): number {
  let low = 0,
    high = model.tokens.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (model.tokens[middle].to <= position) low = middle + 1;
    else high = middle;
  }
  return low - 1;
}
