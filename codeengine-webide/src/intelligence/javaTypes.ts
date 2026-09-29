/** JVM descriptors and generic signatures, decoded locally from downloaded classes. */
export interface TypeRef {
  name: string;
  /** Explicit plugin-owned imports are not indexed by the Paper classpath. */
  opaque?: boolean;
  args?: TypeRef[];
  array?: number;
  variable?: boolean;
  variance?: "+" | "-";
}
export interface MethodType {
  parameters: TypeRef[];
  result: TypeRef;
  variables: string[];
}
const primitive: Record<string, string> = {
  B: "byte",
  C: "char",
  D: "double",
  F: "float",
  I: "int",
  J: "long",
  S: "short",
  Z: "boolean",
  V: "void",
};
class SignatureReader {
  position = 0;
  constructor(readonly source: string) {}
  variables(): string[] {
    const result: string[] = [];
    if (this.source[this.position] !== "<") return result;
    this.position++;
    while (
      this.position < this.source.length &&
      this.source[this.position] !== ">"
    ) {
      const colon = this.source.indexOf(":", this.position);
      if (colon < 0) throw new Error("Invalid generic signature");
      result.push(this.source.slice(this.position, colon));
      this.position = colon;
      while (this.source[this.position] === ":") {
        this.position++;
        if (this.source[this.position] !== ":") this.type();
      }
    }
    if (this.source[this.position++] !== ">")
      throw new Error("Unclosed generic signature");
    return result;
  }
  type(depth = 0): TypeRef {
    if (depth > 64) throw new Error("Signature nesting limit");
    const char = this.source[this.position++];
    if (primitive[char]) return { name: primitive[char] };
    if (char === "[") {
      const type = this.type(depth + 1);
      return { ...type, array: (type.array ?? 0) + 1 };
    }
    if (char === "*") return { name: "?" };
    if (char === "+" || char === "-")
      return { ...this.type(depth + 1), variance: char };
    if (char === "T") {
      const end = this.source.indexOf(";", this.position);
      if (end < 0) throw new Error("Invalid type variable");
      const name = this.source.slice(this.position, end);
      this.position = end + 1;
      return { name, variable: true };
    }
    if (char !== "L") throw new Error("Invalid type signature");
    let name = "";
    const args: TypeRef[] = [];
    while (this.position < this.source.length) {
      const part = this.source[this.position++];
      if (part === ";")
        return {
          name: name.replaceAll("/", "."),
          ...(args.length ? { args } : {}),
        };
      if (part === "<") {
        while (
          this.position < this.source.length &&
          this.source[this.position] !== ">"
        )
          args.push(this.type(depth + 1));
        if (this.source[this.position++] !== ">")
          throw new Error("Unclosed type arguments");
      } else {
        // A nested class declares its own type variables. Outer variables remain
        // unknown instead of being incorrectly bound to the inner declaration.
        if (part === ".") args.length = 0;
        name += part === "." ? "$" : part;
      }
    }
    throw new Error("Unclosed class signature");
  }
}
export function fieldType(descriptor: string, signature?: string): TypeRef {
  if (signature) {
    try {
      return new SignatureReader(signature).type();
    } catch {
      /* Descriptor remains authoritative fallback. */
    }
  }
  return new SignatureReader(descriptor).type();
}
export function methodType(descriptor: string, signature?: string): MethodType {
  function read(value: string): MethodType {
    const reader = new SignatureReader(value);
    const variables = reader.variables();
    if (value[reader.position++] !== "(")
      throw new Error("Invalid method descriptor");
    const parameters: TypeRef[] = [];
    while (reader.position < value.length && value[reader.position] !== ")")
      parameters.push(reader.type());
    if (value[reader.position++] !== ")")
      throw new Error("Unclosed method descriptor");
    return { parameters, result: reader.type(), variables };
  }
  if (signature) {
    try {
      return read(signature);
    } catch {
      /* Fall back to the mandatory descriptor. */
    }
  }
  return read(descriptor);
}
export function classType(
  signature?: string,
): { variables: string[]; parents: TypeRef[] } | null {
  if (!signature) return null;
  try {
    const reader = new SignatureReader(signature);
    const variables = reader.variables(),
      parents: TypeRef[] = [];
    while (reader.position < signature.length) parents.push(reader.type());
    return { variables, parents };
  } catch {
    return null;
  }
}
export function substitute(
  type: TypeRef,
  bindings: Map<string, TypeRef>,
): TypeRef {
  const replacement = type.variable ? bindings.get(type.name) : undefined;
  if (replacement)
    return {
      ...replacement,
      array: (replacement.array ?? 0) + (type.array ?? 0),
    };
  return { ...type, args: type.args?.map((arg) => substitute(arg, bindings)) };
}
export function typeText(type: TypeRef, qualified = false): string {
  const name = qualified
    ? type.name.replaceAll("$", ".")
    : type.name.split(".").at(-1)!.replaceAll("$", ".");
  const text =
    name +
    (type.args?.length
      ? "<" + type.args.map((arg) => typeText(arg, qualified)).join(", ") + ">"
      : "") +
    "[]".repeat(type.array ?? 0);
  return type.variance
    ? "? " + (type.variance === "+" ? "extends " : "super ") + text
    : text;
}
