package kr.codenamemc.codeengine.intelligence;

import java.io.*;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipFile;
import kr.codenamemc.codeengine.compiler.ModuleCompiler;

/** Copies class-file bytes for the browser; it never interprets classes or builds a type index. */
final class StandardApiArtifacts {
    private StandardApiArtifacts() { }

    static String engineFingerprint() throws IOException {
        try (InputStream input = StandardApiArtifacts.class.getResourceAsStream("/intelligence/codeengine-api.bin")) {
            if (input == null) return "unbundled"; // Allows isolated service tests; production loader requires the resource.
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())); }
            catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
        }
    }

    static Path codeEngine(Path cache) throws IOException {
        Path target = cache.resolve("codeengine-api.jar");
        try (InputStream input = StandardApiArtifacts.class.getResourceAsStream("/intelligence/codeengine-api.bin")) {
            if (input == null) throw new IOException("Missing bundled Code Engine API");
            Path temporary = Files.createTempFile(cache, "codeengine-api-", ".tmp");
            try {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                IntelligenceArtifacts.move(temporary, target);
            } finally { Files.deleteIfExists(temporary); }
        }
        return target;
    }

    static Path javaRuntime(Path cache) throws IOException {
        if (Runtime.version().feature() > ModuleCompiler.javaRelease) {
            return javaReleaseSignatures(cache, Path.of(System.getProperty("java.home"), "lib", "ct.sym"), ModuleCompiler.javaRelease);
        }
        Path target = cache.resolve("java-runtime-" + ModuleCompiler.javaRelease + ".jar");
        Path temporary = Files.createTempFile(cache, "java-runtime-", ".tmp");
        try {
            FileSystem runtime = FileSystems.getFileSystem(URI.create("jrt:/"));
            // Include public, unqualified exports of Java SE modules; no JDK internals or server classes.
            var modules = ModuleFinder.ofSystem().findAll().stream()
                .filter(module -> module.descriptor().name().startsWith("java."))
                .sorted(Comparator.comparing(module -> module.descriptor().name())).toList();
            try (JarOutputStream jar = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                for (var module : modules) {
                    Path modulePath = runtime.getPath("/modules", module.descriptor().name());
                    var packages = module.descriptor().exports().stream().filter(export -> !export.isQualified())
                        .map(export -> export.source().replace('.', '/')).sorted().toList();
                    for (String packageName : packages) {
                        interrupted();
                        Path directory = modulePath.resolve(packageName);
                        try (var entries = Files.list(directory)) {
                            for (Path file : entries.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                                interrupted();
                                JarEntry entry = new JarEntry(packageName + "/" + file.getFileName());
                                entry.setTime(0);
                                jar.putNextEntry(entry);
                                Files.copy(file, jar);
                                jar.closeEntry();
                            }
                        }
                    }
                }
            }
            IntelligenceArtifacts.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
        return target;
    }
    /** ct.sym uses class-file signatures for --release; copy them verbatim under .class entry names. */
    static Path javaReleaseSignatures(Path cache, Path symbols, int release) throws IOException {
        Path target = cache.resolve("java-runtime-" + release + ".jar");
        Path temporary = Files.createTempFile(cache, "java-signatures-", ".tmp");
        char releaseCode = Character.toUpperCase(Character.forDigit(release, 36));
        Set<String> copied = new HashSet<>();
        try {
            interrupted();
            try (ZipFile zip = new ZipFile(symbols.toFile());
                 JarOutputStream jar = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    interrupted();
                    var entry = entries.nextElement();
                    String[] parts = entry.getName().split("/", 3);
                    if (entry.isDirectory() || parts.length != 3 || parts[0].indexOf(releaseCode) < 0
                        || !parts[1].startsWith("java.") || !parts[2].endsWith(".sig") || parts[2].equals("module-info.sig")) continue;
                    String name = parts[2].substring(0, parts[2].length() - 4) + ".class";
                    if (!copied.add(name)) throw new IOException("Duplicate Java release signature: " + name);
                    JarEntry output = new JarEntry(name);
                    output.setTime(0);
                    jar.putNextEntry(output);
                    try (InputStream input = zip.getInputStream(entry)) { input.transferTo(jar); }
                    jar.closeEntry();
                }
            }
            if (!copied.contains("java/lang/Object.class")) throw new IOException("The JDK does not include Java " + release + " API signatures");
            IntelligenceArtifacts.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
        return target;
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("API preparation was stopped");
    }
}
