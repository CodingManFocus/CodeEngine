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
