package kr.codenamemc.codeengine.intelligence;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IntelligenceArtifactsTest {
    @TempDir Path directory;

    @Test void constructionAndRequestsNeverRunPreparationOnCallerThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> loaderThread = new AtomicReference<>();
        Path source = Files.write(directory.resolve("paper-api.jar"), new byte[] { 1, 2, 3 });
        Path cache = directory.resolve("cache");
        try (var artifacts = new IntelligenceArtifacts(cache, "test", root -> {
            calls.incrementAndGet(); loaderThread.set(Thread.currentThread()); entered.countDown(); release.await();
            return prepared(source);
        })) {
            assertFalse(Files.exists(cache), "Constructor must not create or inspect a cache");
            assertEquals(0, calls.get());
            assertEquals("loading", artifacts.request().status());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 50; i++) assertEquals("loading", artifacts.request().status());
            assertEquals(1, calls.get());
            assertNotSame(Thread.currentThread(), loaderThread.get());
            assertEquals("CodeEngine-API-Artifacts", loaderThread.get().getName());
            release.countDown();
            var ready = await(artifacts);
            assertEquals("ready", ready.status(), ready.message());
            var descriptor = ready.artifacts().getFirst();
            assertEquals("paper-api.jar", descriptor.name());
            assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(artifacts.artifact(descriptor.id()).path()));
            assertNull(artifacts.artifact("../../config.yml"));
        } finally { release.countDown(); }
    }

    @Test void verifiedWarmCacheWorksOfflineAndPreservesArtifactNames() throws Exception {
        Path source = Files.write(directory.resolve("paper-api.jar"), new byte[] { 10, 20 });
        Path cache = directory.resolve("cache");
        String expectedHash;
        try (var artifacts = new IntelligenceArtifacts(cache, "test", root -> prepared(source))) {
            var ready = await(artifacts);
            assertEquals("ready", ready.status(), ready.message());
            expectedHash = ready.artifacts().getFirst().sha256();
        }
        Files.delete(source);
        try (var artifacts = new IntelligenceArtifacts(cache, "test", root -> { throw new IOException("offline"); })) {
            var ready = await(artifacts);
            assertEquals("ready", ready.status(), ready.message());
            assertEquals(expectedHash, ready.artifacts().getFirst().sha256());
            assertEquals("paper-api.jar", ready.artifacts().getFirst().name());
        }
    }

    @Test void corruptCacheIsRebuiltAndChangedServerVersionCannotReuseIt() throws Exception {
        Path source = Files.write(directory.resolve("paper-api.jar"), new byte[] { 10, 20 });
        Path cache = directory.resolve("cache"), cached;
        try (var artifacts = new IntelligenceArtifacts(cache, "v1", root -> prepared(source))) {
            var ready = await(artifacts);
            cached = artifacts.artifact(ready.artifacts().getFirst().id()).path();
        }
        Files.write(cached, new byte[] { 0, 0 });
        AtomicInteger calls = new AtomicInteger();
        try (var artifacts = new IntelligenceArtifacts(cache, "v1", root -> { calls.incrementAndGet(); return prepared(source); })) {
            assertEquals("ready", await(artifacts).status());
            assertEquals(1, calls.get());
            assertArrayEquals(new byte[] { 10, 20 }, Files.readAllBytes(cached));
        }
        try (var artifacts = new IntelligenceArtifacts(cache, "v2", root -> { throw new IOException("no matching version"); })) {
            var error = await(artifacts);
            assertEquals("error", error.status());
            assertTrue(error.message().contains("no matching version"));
            assertTrue(error.artifacts().isEmpty());
        }
    }

    @Test void failureIsStableWithoutRetryStormAndCloseInterruptsPreparation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var artifacts = new IntelligenceArtifacts(directory.resolve("failed"), "test", root -> {
            calls.incrementAndGet(); throw new IOException("fixture network failed");
        })) {
            assertEquals("error", await(artifacts).status());
            for (int i = 0; i < 20; i++) assertEquals("error", artifacts.request().status());
            assertEquals(1, calls.get());
        }
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        var artifacts = new IntelligenceArtifacts(directory.resolve("cancelled"), "test", root -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); throw new AssertionError("Unexpected release"); }
            catch (InterruptedException error) { interrupted.countDown(); throw error; }
        });
        try {
            artifacts.request(); assertTrue(entered.await(5, TimeUnit.SECONDS)); artifacts.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals("error", artifacts.request().status());
            assertNull(artifacts.artifact("any"));
        } finally { artifacts.close(); }
    }

    @Test void changedServerBuildInvalidatesCacheEvenWhenBukkitVersionIsUnchanged() throws Exception {
        Path source = Files.write(directory.resolve("paper-api.jar"), new byte[] { 1, 2, 3 });
        Path cache = directory.resolve("cache");
        String bukkit = "1.21.11-R0.1-SNAPSHOT";
        try (var artifacts = new IntelligenceArtifacts(cache, bukkit, bukkit + "|1.21.11|Paper build 100", root -> prepared(source))) {
            assertEquals("ready", await(artifacts).status());
        }
        AtomicInteger reloads = new AtomicInteger();
        try (var artifacts = new IntelligenceArtifacts(cache, bukkit, bukkit + "|1.21.11|Paper build 101", root -> {
            reloads.incrementAndGet(); return prepared(source);
        })) {
            assertEquals("ready", await(artifacts).status());
            assertEquals(1, reloads.get(), "A different raw server build must resolve a fresh matching API");
        }
    }

    @Test void explicitRetryCanRecoverButDoesNotDuplicateReadyOrActiveWork() throws Exception {
        Path source = Files.write(directory.resolve("paper-api.jar"), new byte[] { 1, 2, 3 });
        AtomicInteger calls = new AtomicInteger();
        try (var artifacts = new IntelligenceArtifacts(directory.resolve("cache"), "test", root -> {
            if (calls.incrementAndGet() == 1) throw new IOException("offline");
            return prepared(source);
        })) {
            assertEquals("error", await(artifacts).status());
            artifacts.retry();
            assertEquals("ready", await(artifacts).status());
            assertEquals("ready", artifacts.retry().status());
            assertEquals(2, calls.get());
        }
    }

    @Test void oversizedArtifactIsRejectedBeforeHashOrDelivery() throws Exception {
        Path source = directory.resolve("too-large.jar");
        try (var file = new java.io.RandomAccessFile(source.toFile(), "rw")) { file.setLength(IntelligenceArtifacts.maxArtifactBytes + 1); }
        try (var artifacts = new IntelligenceArtifacts(directory.resolve("cache"), "test", root -> prepared(source))) {
            var status = await(artifacts);
            assertEquals("error", status.status()); assertTrue(status.message().contains("size limit"));
            assertTrue(status.artifacts().isEmpty());
        }
    }

    @Test void forkUsesItsLoadedApiJarAndPaperDependenciesOfTheSameMinecraftVersion() throws Exception {
        Path repository = directory.resolve("repository");
        Files.createDirectories(repository.resolve("io/papermc/paper/paper-api"));
        mavenArtifact(repository, "fixture", "adventure", "1", "");
        mavenArtifact(repository, "io.papermc.paper", "paper-api", "26.3.build.40-stable",
            "<dependencies><dependency><groupId>fixture</groupId><artifactId>adventure</artifactId><version>1</version></dependency></dependencies>");
        Path metadata = repository.resolve("io/papermc/paper/paper-api/maven-metadata.xml");
        Files.writeString(metadata, "<metadata><groupId>io.papermc.paper</groupId><artifactId>paper-api</artifactId><versioning><versions>"
            + "<version>26.3.build.40-stable</version><version>26.2.build.900-stable</version></versions></versioning></metadata>");
        sha1(metadata);
        var resolver = new MavenArtifactResolver(List.of(new org.eclipse.aether.repository.RemoteRepository.Builder(
            "fixture", "default", repository.toUri().toString()).build()));

        Path purpurApi = jar(directory.resolve("purpur-api-26.3.jar"), "org/bukkit/Bukkit.class", "org/purpurmc/purpur/PurpurConfig.class");
        var prepared = IntelligenceArtifacts.paperApi(resolver, directory.resolve("maven"), "Purpur",
            "26.3.build.2652-stable", "26.3", "26.3-2652-abcdef (MC: 26.3)", purpurApi);
        assertEquals("fork-runtime", prepared.resolution());
        assertEquals("Purpur 26.3.build.2652-stable", prepared.version());
        assertEquals(List.of("purpur-api-26.3.jar", "adventure-1.jar"),
            prepared.jars().stream().map(path -> path.getFileName().toString()).toList());

        // A shaded server JAR is not an API library: fall back to Paper's API, and say so.
        Path shaded = jar(directory.resolve("server.jar"), "org/bukkit/Bukkit.class", "net/minecraft/server/MinecraftServer.class");
        var fallback = IntelligenceArtifacts.paperApi(resolver, directory.resolve("maven"), "Purpur",
            "26.3.build.2652-stable", "26.3", "", shaded);
        assertEquals("compatible-fork", fallback.resolution());
        assertEquals("26.3.build.40-stable", fallback.version());
        assertEquals(List.of("adventure-1.jar", "paper-api-26.3.build.40-stable.jar"),
            fallback.jars().stream().map(path -> path.getFileName().toString()).sorted().toList());
    }

    private static Path jar(Path target, String... entries) throws IOException {
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(target))) {
            for (String entry : entries) { output.putNextEntry(new java.util.jar.JarEntry(entry)); output.closeEntry(); }
        }
        return target;
    }
    private static void mavenArtifact(Path repository, String group, String name, String version, String body) throws Exception {
        Path folder = Files.createDirectories(repository.resolve(group.replace('.', '/') + "/" + name + "/" + version));
        Path pom = Files.writeString(folder.resolve(name + "-" + version + ".pom"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + "<modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + name + "</artifactId><version>"
            + version + "</version>" + body + "</project>");
        sha1(pom);
        sha1(jar(folder.resolve(name + "-" + version + ".jar"), name + ".txt"));
    }
    private static void sha1(Path file) throws Exception {
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
        Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"), digest);
    }

    private static IntelligenceArtifacts.Prepared prepared(Path source) {
        return new IntelligenceArtifacts.Prepared("1.21.11-R0.1-SNAPSHOT", "compatible-snapshot", "fixture", List.of(source));
    }
    private static IntelligenceArtifacts.State await(IntelligenceArtifacts artifacts) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        IntelligenceArtifacts.State state;
        while ("loading".equals((state = artifacts.request()).status()) && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotEquals("loading", state.status(), "API preparation timed out");
        return state;
    }
}
