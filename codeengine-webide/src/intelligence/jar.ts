import { parseClassFile } from "./classfile";
import { Access, type JavaClass } from "./types";

export interface JarOptions {
  javaVersion?: number;
  maxEntries?: number;
  maxClassBytes?: number;
  maxTotalBytes?: number;
}

interface Entry {
  name: string;
  nameBytes: Uint8Array;
  flags: number;
  method: number;
  crc: number;
  compressed: number;
  size: number;
  offset: number;
}

const crcTable = new Uint32Array(256);
for (let i = 0; i < 256; i++) {
  let crc = i;
  for (let n = 0; n < 8; n++)
    crc = crc & 1 ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1;
  crcTable[i] = crc >>> 0;
}
function crc32(bytes: Uint8Array) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

/** Read JAR metadata in a worker, without executing classes or using server indexes. */
export async function parseJar(
  buffer: ArrayBuffer,
  options: JarOptions = {},
): Promise<JavaClass[]> {
  const javaVersion = options.javaVersion ?? 21;
  const maxEntries = options.maxEntries ?? 65_534;
  const maxClassBytes = options.maxClassBytes ?? 8 * 1024 * 1024;
  const maxTotalBytes = options.maxTotalBytes ?? 192 * 1024 * 1024;
  for (const limit of [javaVersion, maxEntries, maxClassBytes, maxTotalBytes])
    if (!Number.isSafeInteger(limit) || limit < 1)
      throw new Error("Invalid JAR reader limit");
  if (buffer.byteLength > maxTotalBytes)
    throw new Error("JAR download exceeds size limit");
  const bytes = new Uint8Array(buffer),
    view = new DataView(buffer);
  const need = (offset: number, size: number) => {
    if (
      !Number.isSafeInteger(offset) ||
      !Number.isSafeInteger(size) ||
      offset < 0 ||
      size < 0 ||
      offset > bytes.length - size
    )
      throw new Error("Truncated JAR archive");
  };
  const u2 = (offset: number) => {
    need(offset, 2);
    return view.getUint16(offset, true);
  };
  const u4 = (offset: number) => {
    need(offset, 4);
    return view.getUint32(offset, true);
  };
  let end = -1;
  // The EOCD is followed only by the optional, at most 65535-byte ZIP comment.
  for (
    let i = bytes.length - 22;
    i >= Math.max(0, bytes.length - 22 - 65_535);
    i--
  ) {
    if (u4(i) === 0x06054b50 && i + 22 + u2(i + 20) === bytes.length) {
      end = i;
      break;
    }
  }
  if (end < 0) throw new Error("Missing JAR central directory");
  const count = u2(end + 10),
    centralSize = u4(end + 12),
    centralOffset = u4(end + 16);
  if (u2(end + 4) !== 0 || u2(end + 6) !== 0 || u2(end + 8) !== count)
    throw new Error("Split JAR archives are unsupported");
  if (
    count === 0xffff ||
    centralSize === 0xffffffff ||
    centralOffset === 0xffffffff
  )
    throw new Error("ZIP64 JAR archives are unsupported");
  if (count > maxEntries) throw new Error("JAR contains too many entries");
  need(centralOffset, centralSize);
  if (centralOffset + centralSize !== end)
    throw new Error("Invalid JAR central directory bounds");
  const entries: Entry[] = [],
    paths = new Set<string>(),
    decoder = new TextDecoder("utf-8", { fatal: true });
  let position = centralOffset;
  for (let i = 0; i < count; i++) {
    need(position, 46);
    if (u4(position) !== 0x02014b50)
      throw new Error("Invalid JAR central directory entry");
    const flags = u2(position + 8),
      method = u2(position + 10),
      compressed = u4(position + 20),
      size = u4(position + 24);
    const nameLength = u2(position + 28),
      extraLength = u2(position + 30),
      commentLength = u2(position + 32),
      offset = u4(position + 42);
    need(position + 46, nameLength + extraLength + commentLength);
    if (
      compressed === 0xffffffff ||
      size === 0xffffffff ||
      offset === 0xffffffff ||
      u2(position + 34) !== 0
    )
      throw new Error("ZIP64 or split JAR entries are unsupported");
    const nameBytes = bytes.subarray(position + 46, position + 46 + nameLength);
    const name = decoder.decode(nameBytes);
    if (paths.has(name)) throw new Error(`Duplicate JAR entry: ${name}`);
    paths.add(name);
    entries.push({
      name,
      nameBytes,
      flags,
      method,
      compressed,
      size,
      offset,
      crc: u4(position + 16),
    });
    position += 46 + nameLength + extraLength + commentLength;
    if (position > end) throw new Error("JAR entry exceeds central directory");
  }
  if (position !== end) throw new Error("Incorrect JAR central directory size");

  const extract = async (entry: Entry, limit: number): Promise<Uint8Array> => {
    if (entry.size > limit || entry.compressed > maxTotalBytes)
      throw new Error(`JAR entry exceeds size limit: ${entry.name}`);
    if (entry.flags & (1 | 0x40))
      throw new Error("Encrypted JAR entries are unsupported");
    need(entry.offset, 30);
    if (
      u4(entry.offset) !== 0x04034b50 ||
      u2(entry.offset + 6) !== entry.flags ||
      u2(entry.offset + 8) !== entry.method
    )
      throw new Error("JAR local header does not match central directory");
    const nameLength = u2(entry.offset + 26),
      extraLength = u2(entry.offset + 28);
    const start = entry.offset + 30 + nameLength + extraLength;
    need(entry.offset + 30, nameLength + extraLength);
    need(start, entry.compressed);
    if (
      start + entry.compressed > centralOffset ||
      nameLength !== entry.nameBytes.length
    )
      throw new Error("Invalid JAR entry data bounds");
    for (let i = 0; i < nameLength; i++)
      if (bytes[entry.offset + 30 + i] !== entry.nameBytes[i])
        throw new Error("JAR entry name mismatch");
    if (
      !(entry.flags & 8) &&
      (u4(entry.offset + 14) !== entry.crc ||
        u4(entry.offset + 18) !== entry.compressed ||
        u4(entry.offset + 22) !== entry.size)
    )
      throw new Error("JAR entry size or checksum metadata mismatch");
    let output: Uint8Array;
    if (entry.method === 0) {
      if (entry.compressed !== entry.size)
        throw new Error("Invalid stored JAR entry size");
      output = bytes.subarray(start, start + entry.compressed);
    } else if (entry.method === 8) {
      // Read incrementally and stop at the advertised uncompressed size. This
      // bounds expansion even if hostile DEFLATE data lies about its size.
      let stream: DecompressionStream;
      try {
        stream = new DecompressionStream("deflate-raw");
      } catch {
        throw new Error(
          "This browser does not support JAR decompression (deflate-raw)",
        );
      }
      const source = new Blob([buffer.slice(start, start + entry.compressed)])
        .stream()
        .pipeThrough(stream)
        .getReader();
      output = new Uint8Array(entry.size);
      let length = 0;
      try {
        while (true) {
          const chunk = await source.read();
          if (chunk.done) break;
          if (length + chunk.value.byteLength > entry.size)
            throw new Error("JAR entry expanded beyond declared size");
          output.set(chunk.value, length);
          length += chunk.value.byteLength;
        }
        if (length !== entry.size)
          throw new Error("JAR entry uncompressed size mismatch");
      } catch (error) {
        await source.cancel().catch(() => {});
        throw error;
      } finally {
        source.releaseLock();
      }
    } else
      throw new Error(`Unsupported JAR compression method ${entry.method}`);
    if (crc32(output) !== entry.crc)
      throw new Error("JAR entry checksum mismatch");
    return output;
  };

  const manifest = entries.find(
    (entry) => entry.name.toUpperCase() === "META-INF/MANIFEST.MF",
  );
  let multiRelease = false;
  if (manifest) {
    const text = new TextDecoder().decode(await extract(manifest, 1024 * 1024));
    const mainSection = text
      .replace(/\r\n/g, "\n")
      .replace(/\n /g, "")
      .split("\n\n", 1)[0];
    multiRelease = /^Multi-Release:\s*true\s*$/im.test(mainSection);
  }
  const selected = new Map<string, { entry: Entry; version: number }>();
  for (const entry of entries) {
    if (!entry.name.endsWith(".class")) continue;
    if (
      entry.name.startsWith("/") ||
      entry.name.includes("\\") ||
      entry.name
        .split("/")
        .some((part) => part === "." || part === ".." || part === "")
    )
      throw new Error("Invalid JAR class path");
    let path = entry.name,
      version = 0;
    if (path.startsWith("META-INF/versions/")) {
      const match = /^META-INF\/versions\/(\d+)\/(.+)$/.exec(path);
      if (!multiRelease || !match) continue;
      version = Number(match[1]);
      if (version < 9 || version > javaVersion) continue;
      path = match[2];
    } else if (path.startsWith("META-INF/")) continue;
    if (
      path === "module-info.class" ||
      path.endsWith("/package-info.class") ||
      path === "package-info.class"
    )
      continue;
    const previous = selected.get(path);
    if (!previous || previous.version < version)
      selected.set(path, { entry, version });
  }
  let total = 0;
  for (const { entry } of selected.values()) {
    total += entry.size;
    if (entry.size > maxClassBytes || total > maxTotalBytes)
      throw new Error("JAR classes exceed indexing size limit");
  }
  const classes: JavaClass[] = [];
  for (const [path, { entry }] of selected) {
    const parsed = parseClassFile(await extract(entry, maxClassBytes));
    if (parsed.name.replaceAll(".", "/") + ".class" !== path)
      throw new Error(`JAR class name does not match entry: ${entry.name}`);
    if (
      parsed.access & (Access.PUBLIC | Access.PROTECTED) &&
      !(parsed.access & Access.SYNTHETIC)
    )
      classes.push(parsed);
  }
  return classes;
}
