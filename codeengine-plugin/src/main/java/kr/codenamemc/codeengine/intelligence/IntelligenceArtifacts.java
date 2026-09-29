package kr.codenamemc.codeengine.intelligence;

import com.google.gson.Gson;
import kr.codenamemc.codeengine.compiler.ModuleCompiler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Supplies immutable JARs. All preparation and disk access happens on a dedicated worker. */
public final class IntelligenceArtifacts implements AutoCloseable {
    // One plugin has one editor, but a stopped worker may still be unwinding during a reopen.
    // Only background workers take this lock; shutdown and tick threads never wait for it.
    private static final java.util.concurrent.locks.ReentrantLock preparationLock = new java.util.concurrent.locks.ReentrantLock();
    public static final long maxArtifactBytes = 64L * 1024 * 1024;
    public static final long maxTotalBytes = 192L * 1024 * 1024;
    public static final int maxArtifacts = 96;
    public record Artifact(String id, String name, String sha256, long size, String url) { }
    public record State(String status, String message, String version, String requestedVersion,
                        String resolution, int javaVersion, List<Artifact> artifacts) { }
    public record FileArtifact(Artifact descriptor, Path path) { }
    public record Prepared(String version, String resolution, String message, List<Path> jars) { }
    @FunctionalInterface public interface Loader { Prepared load(Path cache) throws Exception; }
    private record Cached(String identity, String version, String resolution, String message, List<CachedFile> files) { }
    private record CachedFile(String name, String sha256, long size) { }

    private final Path cache;
    private final String requestedVersion;
    private final String cacheIdentity;
    private final Loader loader;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile State state;
    private volatile Map<String, FileArtifact> files = Map.of();
    private final Map<Path, String> cachedNames = new HashMap<>();

    /** This constructor performs no filesystem, network, JAR, or class scanning work. */
    public IntelligenceArtifacts(Path cache, String requestedVersion, Loader loader) {
        this(cache, requestedVersion, requestedVersion, loader);
    }
    IntelligenceArtifacts(Path cache, String requestedVersion, String cacheIdentity, Loader loader) {
        this.cache = cache;
        this.requestedVersion = requestedVersion;
        this.cacheIdentity = cacheIdentity;
        this.loader = loader;
        this.state = status("loading", "Preparing Java API artifacts", "", "");
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "CodeEngine-API-Artifacts");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static IntelligenceArtifacts paper(Path cache, String bukkitVersion, String minecraftVersion, String serverVersion) {
        // Version parsing is intentionally deferred too: unsupported versions become an editor status.
        return new IntelligenceArtifacts(cache, bukkitVersion, bukkitVersion + "|" + minecraftVersion + "|" + serverVersion, root -> {
            PaperApiVersion target = PaperApiVersion.from(bukkitVersion, minecraftVersion, serverVersion);
            List<Path> jars = new ArrayList<>(new MavenArtifactResolver().resolve(target.version(), root.resolve("maven")));
            jars.add(StandardApiArtifacts.codeEngine(root));
            jars.add(StandardApiArtifacts.javaRuntime(root));
            return new Prepared(target.version(), target.compatibility(), target.detail(), jars);
        });
    }

    /** Nonblocking and safe even if accidentally called on the server tick thread. */
    public State request() {
        if (closed.get()) return status("error", "WebIDE is stopped", "", "");
        if (started.compareAndSet(false, true)) {
            try { worker.execute(this::prepare); }
            catch (RejectedExecutionException error) { state = status("error", "API preparation is unavailable", "", ""); }
        }
        return state;
    }

    public synchronized State retry() {
        if ("error".equals(state.status()) && !closed.get()) {
            state = status("loading", "Preparing Java API artifacts", "", "");
            started.set(false);
        }
        return request();
    }

    /** Only registered opaque IDs are accepted; user input never becomes a path or URL. */
    public FileArtifact artifact(String id) {
        if (closed.get() || !"ready".equals(state.status())) return null;
        return id == null ? null : files.get(id);
    }

    private void prepare() {
        boolean locked = false;
        try {
            preparationLock.lockInterruptibly();
            locked = true;
            checkInterrupted();
            Files.createDirectories(cache);
            String identity = "v2|" + ModuleCompiler.javaRelease + "|" + cacheIdentity + "|" + Runtime.version() + "|" + System.getProperty("java.vendor") + "|" + StandardApiArtifacts.engineFingerprint();
            clearStaleCache(identity);
            Path manifest = cache.resolve("manifest.json");
            Prepared prepared = readCache(manifest, identity);
            if (prepared == null) prepared = loader.load(cache);
            checkInterrupted();
            if (prepared.jars().isEmpty() || prepared.jars().size() > maxArtifacts) throw new IOException("Unexpected API artifact count");
            List<Artifact> descriptors = new ArrayList<>();
            Map<String, FileArtifact> preparedFiles = new LinkedHashMap<>();
            List<CachedFile> cachedFiles = new ArrayList<>();
            Set<String> filenames = new HashSet<>();
            long total = 0;
            Path jarsDirectory = cache.resolve("jars");
            Files.createDirectories(jarsDirectory);
            for (Path source : prepared.jars()) {
                checkInterrupted();
                long size = Files.size(source);
                if (size <= 0 || size > maxArtifactBytes || (total += size) > maxTotalBytes) throw new IOException("API artifacts exceed the size limit");
                String hash = sha256(source);
                String name = cachedNames.getOrDefault(source, source.getFileName().toString());
                if (!name.matches("[A-Za-z0-9._+-]+\\.jar")) throw new IOException("Invalid artifact filename");
                // Content-addressed copies keep a snapshot stable while the Maven cache refreshes.
                String filename = hash + ".jar";
                Path target = jarsDirectory.resolve(filename);
                if (!source.equals(target) && (!Files.isRegularFile(target) || !hash.equals(sha256(target)))) copyAtomic(source, target);
                String id = hash;
                if (!filenames.add(id)) continue;
                Artifact descriptor = new Artifact(id, name, hash, size, "/api/intelligence/artifact?id=" + id);
                descriptors.add(descriptor);
                preparedFiles.put(id, new FileArtifact(descriptor, target));
                cachedFiles.add(new CachedFile(name, hash, size));
            }
            writeAtomic(manifest, new Gson().toJson(new Cached(identity, prepared.version(), prepared.resolution(), prepared.message(), cachedFiles)));
            // A stop racing with preparation cannot publish a usable artifact set.
            checkInterrupted();
            files = Map.copyOf(preparedFiles);
            state = new State("ready", prepared.message(), prepared.version(), requestedVersion,
                prepared.resolution(), ModuleCompiler.javaRelease, List.copyOf(descriptors));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            files = Map.of();
            state = status("error", "Could not prepare API artifacts: " + safeMessage(error), "", "");
        } finally {
            if (locked) preparationLock.unlock();
        }
    }

    private void clearStaleCache(String identity) throws IOException, InterruptedException {
        boolean clear = false;
        Path manifest = cache.resolve("manifest.json");
        if (Files.isRegularFile(manifest)) {
            try {
                if (Files.size(manifest) > 128 * 1024) clear = true;
                else {
                    Cached saved = new Gson().fromJson(Files.readString(manifest), Cached.class);
                    clear = saved == null || !identity.equals(saved.identity());
                }
            } catch (RuntimeException invalid) { clear = true; }
        }
        // Bound accumulated failed downloads and replace caches across Java/Paper/API changes.
        long size = 0;
        try (var paths = Files.walk(cache)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                checkInterrupted();
                size += Files.size(path);
                if (size > maxTotalBytes * 3) { clear = true; break; }
            }
        }
        if (clear) {
            try (var paths = Files.walk(cache)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    checkInterrupted();
                    if (!path.equals(cache)) Files.deleteIfExists(path);
                }
            }
        }
    }

    private Prepared readCache(Path manifest, String identity) throws IOException, InterruptedException {
        if (!Files.isRegularFile(manifest)) return null;
        try {
            if (Files.size(manifest) > 128 * 1024) return null;
            Cached saved = new Gson().fromJson(Files.readString(manifest), Cached.class);
            if (saved == null || !identity.equals(saved.identity()) || saved.version() == null || saved.resolution() == null
                || saved.message() == null || saved.files() == null || saved.files().isEmpty() || saved.files().size() > maxArtifacts) return null;
            List<Path> paths = new ArrayList<>();
            long total = 0;
            for (CachedFile entry : saved.files()) {
                checkInterrupted();
                if (entry.sha256() == null || !entry.sha256().matches("[0-9a-f]{64}") || entry.size() <= 0
                    || entry.size() > maxArtifactBytes || (total += entry.size()) > maxTotalBytes) return null;
                Path source = cache.resolve("jars").resolve(entry.sha256() + ".jar");
                if (!Files.isRegularFile(source) || Files.size(source) != entry.size() || !sha256(source).equals(entry.sha256())) return null;
                // Preserve original display names without copying bytes on a warm cache.
                if (entry.name() == null || !entry.name().matches("[A-Za-z0-9._+-]+\\.jar")) return null;
                cachedNames.put(source, entry.name());
                paths.add(source);
            }
            return new Prepared(saved.version(), saved.resolution(), saved.message(), paths);
        } catch (RuntimeException | IOException error) {
            // A corrupt/incomplete cache is rebuilt by the normal bounded resolver path.
            return null;
        }
    }

    private State status(String status, String message, String version, String resolution) {
        return new State(status, message, version, requestedVersion, resolution, ModuleCompiler.javaRelease, List.of());
    }
    private void checkInterrupted() throws InterruptedException {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("API preparation was stopped");
    }
    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[32768];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("API preparation was stopped");
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    static void copyAtomic(Path source, Path target) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "artifact-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void writeAtomic(Path target, String contents) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "manifest-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() {
        closed.set(true);
        files = Map.of();
        worker.shutdownNow();
    }
}
