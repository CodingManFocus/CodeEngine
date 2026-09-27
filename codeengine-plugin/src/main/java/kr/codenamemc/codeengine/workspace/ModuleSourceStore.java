package kr.codenamemc.codeengine.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Flat module namespace eliminates user-controlled directory traversal. */
public final class ModuleSourceStore {
    public static final int maxBytes = 262144;
    private final Path root;
    public ModuleSourceStore(Path root) throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Module source directory cannot be a symlink");
        this.root = root.toRealPath();
    }
    public static String validateId(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9_]{0,47}")) throw new IllegalArgumentException("Invalid module id");
        return id;
    }
    private Path resolve(String id) throws IOException {
        Path path = root.resolve(validateId(id) + ".ce");
        if (Files.isSymbolicLink(path)) throw new IOException("Symlinks are not allowed");
        return path;
    }
    public synchronized List<String> list() throws IOException {
        try (var stream = Files.list(root)) {
            return stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .map(p -> p.getFileName().toString()).filter(n -> n.matches("[a-z][a-z0-9_]{0,47}\\.ce"))
                .map(n -> n.substring(0, n.length() - 3)).sorted().toList();
        }
    }
    public synchronized Snapshot read(String id) throws IOException {
        Path path = resolve(id);
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(maxBytes + 1); }
        if (bytes.length > maxBytes) throw new IOException("Source exceeds 256 KiB");
        return new Snapshot(new String(bytes, StandardCharsets.UTF_8), hash(bytes));
    }
    public synchronized Snapshot save(String id, String source, String expectedRevision) throws IOException {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IOException("Source exceeds 256 KiB");
        Path path = resolve(id);
        String actual = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? read(id).revision() : "new";
        if (!actual.equals(expectedRevision)) throw new ConflictException();
        Path temporary = Files.createTempFile(root, ".save-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("Atomic saves are not supported on this filesystem", e); }
        } finally { Files.deleteIfExists(temporary); }
        return new Snapshot(source, hash(bytes));
    }
    public synchronized void delete(String id, String expectedRevision) throws IOException {
        if (!read(id).revision().equals(expectedRevision)) throw new ConflictException();
        Files.delete(resolve(id));
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public record Snapshot(String source, String revision) { }
    public static final class ConflictException extends IOException {
        private static final long serialVersionUID = 1L;
        public ConflictException() { super("File changed. Reopen it before saving or deleting."); }
    }
}
