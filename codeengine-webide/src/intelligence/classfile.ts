import { Access, type JavaClass, type JavaMember } from "./types";

/** A bounded, metadata-only JVM class reader. Bytecode is never evaluated. */
class Reader {
  private readonly view: DataView;
  private position = 0;

  constructor(private readonly bytes: Uint8Array) {
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  get remaining() {
    return this.bytes.length - this.position;
  }

  private require(size: number) {
    if (!Number.isSafeInteger(size) || size < 0 || size > this.remaining)
      throw new Error("Truncated JVM class file");
  }

  u1() {
    this.require(1);
    return this.view.getUint8(this.position++);
  }
  u2() {
    this.require(2);
    const value = this.view.getUint16(this.position);
    this.position += 2;
    return value;
  }
  u4() {
    this.require(4);
    const value = this.view.getUint32(this.position);
    this.position += 4;
    return value;
  }
  i4() {
    this.require(4);
    const value = this.view.getInt32(this.position);
    this.position += 4;
    return value;
  }
  f4() {
    this.require(4);
    const value = this.view.getFloat32(this.position);
    this.position += 4;
    return value;
  }
  f8() {
    this.require(8);
    const value = this.view.getFloat64(this.position);
    this.position += 8;
    return value;
  }
  i8() {
    this.require(8);
    const value = this.view.getBigInt64(this.position);
    this.position += 8;
    return value.toString();
  }
  take(size: number) {
    this.require(size);
    const bytes = this.bytes.subarray(this.position, this.position + size);
    this.position += size;
    return bytes;
  }
  end() {
    if (this.remaining)
      throw new Error("Unexpected bytes in JVM class metadata");
  }
}

// CONSTANT_Utf8 uses *modified* UTF-8: NUL is C0 80, and supplementary
// characters are encoded as two three-byte UTF-16 surrogate code units.
function modifiedUtf8(bytes: Uint8Array): string {
  const chunks: string[] = [],
    units: number[] = [];
  for (let i = 0; i < bytes.length;) {
    const first = bytes[i++];
    let unit: number;
    if (first >= 1 && first <= 0x7f) unit = first;
    else if ((first & 0xe0) === 0xc0) {
      const second = bytes[i++];
      if (second === undefined || (second & 0xc0) !== 0x80)
        throw new Error("Invalid modified UTF-8");
      unit = ((first & 0x1f) << 6) | (second & 0x3f);
      if (unit < 0x80 && unit !== 0) throw new Error("Overlong modified UTF-8");
    } else if ((first & 0xf0) === 0xe0) {
      const second = bytes[i++],
        third = bytes[i++];
      if (
        second === undefined ||
        third === undefined ||
        (second & 0xc0) !== 0x80 ||
        (third & 0xc0) !== 0x80
      )
        throw new Error("Invalid modified UTF-8");
      unit = ((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f);
      if (unit < 0x800) throw new Error("Overlong modified UTF-8");
    } else throw new Error("Invalid modified UTF-8");
    units.push(unit);
    if (units.length === 4096) {
      chunks.push(String.fromCharCode(...units));
      units.length = 0;
    }
  }
  chunks.push(String.fromCharCode(...units));
  return chunks.join("");
}

type Constant = {
  tag: number;
  text?: string;
  index?: number;
  value?: number | string;
};

export function parseClassFile(buffer: Uint8Array | ArrayBuffer): JavaClass {
  const reader = new Reader(
    buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer),
  );
  if (reader.u4() !== 0xcafebabe)
    throw new Error("Invalid JVM class file magic");
  reader.u2(); // Minor version: preview class metadata is also safe to inspect.
  if (reader.u2() < 45) throw new Error("Unsupported JVM class file version");
  const count = reader.u2(),
    pool: (Constant | undefined)[] = new Array(count);
  if (count === 0) throw new Error("Invalid JVM constant pool");
  for (let i = 1; i < count; i++) {
    const tag = reader.u1(),
      entry: Constant = { tag };
    pool[i] = entry;
    switch (tag) {
      case 1:
        entry.text = modifiedUtf8(reader.take(reader.u2()));
        break;
      case 3:
        entry.value = reader.i4();
        break;
      case 4:
        entry.value = reader.f4();
        break;
      case 5:
        entry.value = reader.i8();
        if (++i >= count) throw new Error("Invalid wide constant");
        break;
      case 6:
        entry.value = reader.f8();
        if (++i >= count) throw new Error("Invalid wide constant");
        break;
      case 7:
      case 8:
      case 16:
      case 19:
      case 20:
        entry.index = reader.u2();
        break;
      case 9:
      case 10:
      case 11:
      case 12:
      case 17:
      case 18:
        reader.take(4);
        break;
      case 15:
        reader.take(3);
        break;
      default:
        throw new Error(`Unsupported JVM constant tag ${tag}`);
    }
  }
  const utf8 = (index: number): string => {
    const entry = pool[index];
    if (entry?.tag !== 1 || entry.text === undefined)
      throw new Error("Invalid JVM UTF-8 reference");
    return entry.text;
  };
  const className = (index: number): string => {
    const entry = pool[index];
    if (entry?.tag !== 7 || entry.index === undefined)
      throw new Error("Invalid JVM class reference");
    return utf8(entry.index).replaceAll("/", ".");
  };
  const constant = (index: number): string | number => {
    const entry = pool[index];
    if (entry?.tag === 8 && entry.index !== undefined) return utf8(entry.index);
    if (entry && [3, 4, 5, 6].includes(entry.tag) && entry.value !== undefined)
      return entry.value;
    throw new Error("Invalid JVM constant value");
  };
  const attributes = (
    target: Pick<
      JavaMember,
      "deprecated" | "signature" | "parameterNames" | "constantValue"
    >,
    isClass = false,
  ) => {
    for (let i = reader.u2(); i > 0; i--) {
      const name = utf8(reader.u2()),
        data = new Reader(reader.take(reader.u4()));
      switch (name) {
        case "Signature":
          target.signature = utf8(data.u2());
          data.end();
          break;
        case "Deprecated":
          target.deprecated = true;
          data.end();
          break;
        case "ConstantValue":
          target.constantValue = constant(data.u2());
          data.end();
          break;
        case "MethodParameters": {
          const names: string[] = [];
          for (let n = data.u1(); n > 0; n--) {
            const index = data.u2();
            names.push(index ? utf8(index) : "");
            data.u2();
          }
          target.parameterNames = names;
          data.end();
          break;
        }
        case "InnerClasses":
          if (isClass) {
            for (let n = data.u2(); n > 0; n--) {
              const innerIndex = data.u2();
              data.u2();
              data.u2();
              const flags = data.u2();
              if (innerIndex && className(innerIndex) === result.name)
                result.access = flags;
            }
            data.end();
          }
          break;
        // Code, annotations, debug tables and resources are intentionally not indexed.
      }
    }
  };
  const access = reader.u2(),
    name = className(reader.u2()),
    superIndex = reader.u2();
  const result: JavaClass = {
    name,
    superName: superIndex ? className(superIndex) : undefined,
    access,
    interfaces: [],
    deprecated: false,
    fields: [],
    methods: [],
  };
  for (let i = reader.u2(); i > 0; i--)
    result.interfaces.push(className(reader.u2()));
  const readMembers = (): JavaMember[] => {
    const members: JavaMember[] = [];
    for (let i = reader.u2(); i > 0; i--) {
      const member: JavaMember = {
        access: reader.u2(),
        name: utf8(reader.u2()),
        descriptor: utf8(reader.u2()),
        deprecated: false,
      };
      attributes(member);
      if (
        member.access & (Access.PUBLIC | Access.PROTECTED) &&
        !(member.access & Access.SYNTHETIC) &&
        member.name !== "<clinit>"
      )
        members.push(member);
    }
    return members;
  };
  result.fields = readMembers();
  result.methods = readMembers();
  attributes(result, true);
  reader.end();
  return result;
}
