package kr.codenamemc.codeengine.intelligence;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** The API JAR the running server loaded Bukkit from: the only exact API source for a Paper fork. */
final class ServerApiJar {
    private ServerApiJar() { }

    /**
     * Returns the JAR when it is a standalone API library, or null for anything else, such as a
     * shaded server JAR or a development class directory. Runs on the API worker only.
     */
    static Path standalone(Path jar) throws IOException {
        if (jar == null || !Files.isRegularFile(jar)) return null;
        Path real = jar.toRealPath();
        if (!real.getFileName().toString().matches("[A-Za-z0-9._+-]+\\.jar")) return null;
        long size = Files.size(real);
        if (size <= 0 || size > IntelligenceArtifacts.maxArtifactBytes) return null;
        try (ZipFile zip = new ZipFile(real.toFile())) {
            if (zip.getEntry("org/bukkit/Bukkit.class") == null) return null;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("API preparation was stopped");
                String name = entries.nextElement().getName();
                // Server internals are neither API nor redistributable intelligence material.
                if (name.startsWith("net/minecraft/") || name.startsWith("org/bukkit/craftbukkit/")) return null;
            }
        } catch (ZipException invalid) {
            return null;
        }
        return real;
    }
}
