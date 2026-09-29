package kr.codenamemc.codeengine.intelligence;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StandardApiArtifactsTest {
    @TempDir Path temporary;

    @Test void javaRuntimeContainsExportedJavaSeTypesAndOmitsJdkInternals() throws Exception {
        Path artifact = StandardApiArtifacts.javaRuntime(temporary);
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (String type : List.of("java/lang/String.class", "java/util/List.class", "java/sql/Connection.class",
                    "org/w3c/dom/Document.class")) {
                var entry = jar.getJarEntry(type);
                assertNotNull(entry, type);
                try (var input = jar.getInputStream(entry)) {
                    assertArrayEquals(new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe}, input.readNBytes(4));
                }
            }
            assertNull(jar.getJarEntry("jdk/internal/misc/Unsafe.class"));
            assertNull(jar.getJarEntry("sun/misc/Unsafe.class"));
            assertNull(jar.getJarEntry("module-info.class"));
        }
    }

    @Test void bundledEngineApiIsAnOpaqueJarWithAStableFingerprint() throws Exception {
        String fingerprint = StandardApiArtifacts.engineFingerprint();
        assertTrue(fingerprint.matches("[a-f0-9]{64}"));
        Path artifact = StandardApiArtifacts.codeEngine(temporary);
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertNotNull(jar.getJarEntry("kr/codenamemc/codeengine/api/CodeModule.class"));
            assertNotNull(jar.getJarEntry("kr/codenamemc/codeengine/api/ModuleContext.class"));
            assertNull(jar.getJarEntry("kr/codenamemc/codeengine/CodeEnginePlugin.class"));
        }
        assertEquals(fingerprint, StandardApiArtifacts.engineFingerprint());
    }

    @Test void releaseSignaturesSelectOnlyTheRequestedReleaseAndCopyBytesVerbatim() throws Exception {
        Path symbols = temporary.resolve("ct.sym");
        byte[] object = new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 1};
        byte[] selected = new byte[]{7, 8, 9};
        byte[] shared = new byte[]{10, 11};
        Map<String, byte[]> entries = Map.of(
            "L/java.base/java/lang/Object.sig", object,
            "L/java.base/java/lang/Selected.sig", selected,
            "KL/java.sql/java/sql/Shared.sig", shared,
            "M/java.base/java/lang/After21.sig", new byte[]{12},
            "K/java.base/java/lang/Before21.sig", new byte[]{13},
            "L/jdk.compiler/com/sun/tools/Compiler.sig", new byte[]{14},
            "L/java.base/module-info.sig", new byte[]{15});
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(symbols))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        Path artifact = StandardApiArtifacts.javaReleaseSignatures(temporary, symbols, 21);
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertEquals(List.of("java/lang/Object.class", "java/lang/Selected.class", "java/sql/Shared.class"),
                jar.stream().map(ZipEntry::getName).sorted().toList());
            for (var entry : Map.of("java/lang/Object.class", object, "java/lang/Selected.class", selected,
                    "java/sql/Shared.class", shared).entrySet()) {
                try (var input = jar.getInputStream(jar.getJarEntry(entry.getKey()))) {
                    assertArrayEquals(entry.getValue(), input.readAllBytes());
                }
            }
        }
    }

    @Test void interruptedRuntimeCopyDoesNotPublishOrLeaveTemporaryFiles() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> StandardApiArtifacts.javaRuntime(temporary));
            assertTrue(Thread.currentThread().isInterrupted());
            try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
        } finally {
            Thread.interrupted();
        }
    }
}
