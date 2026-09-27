package kr.codenamemc.codeengine.workspace;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ScriptStoreTest {
    @TempDir Path directory;
    @Test void atomicSaveDetectsStaleEditors() throws Exception {
        var store = new ScriptStore(directory);
        var original = store.save("hello", "module hello;", "new");
        var updated = store.save("hello", "module hello;\n", original.revision());
        assertNotEquals(original.revision(), updated.revision());
        assertThrows(ScriptStore.ConflictException.class, () -> store.save("hello", "lost data", original.revision()));
        assertEquals(updated, store.read("hello"));
        assertThrows(ScriptStore.ConflictException.class, () -> store.save("hello", "", "new"));
    }
    @ParameterizedTest @ValueSource(strings = {"../x", "x/../hello", "/tmp/x", "a\\b", ".", "..", "UPPER", "a-b", "a.ce"})
    void rejectsTraversalAndAmbiguousNames(String id) throws Exception {
        var store = new ScriptStore(directory); assertThrows(IllegalArgumentException.class, () -> store.read(id));
    }
    @Test void rejectsSymlinkReadsAndWrites() throws Exception {
        var store = new ScriptStore(directory);
        Path target = Files.createTempFile(directory, "target", ".txt"); Files.writeString(target, "untouched");
        Files.createSymbolicLink(directory.resolve("linked.ce"), target);
        assertThrows(java.io.IOException.class, () -> store.read("linked"));
        assertThrows(java.io.IOException.class, () -> store.save("linked", "bad", "new"));
        assertEquals("untouched", Files.readString(target)); assertTrue(store.list().isEmpty());
    }
    @Test void enforcesUtf8ByteLimit() throws Exception {
        var store = new ScriptStore(directory);
        assertThrows(java.io.IOException.class, () -> store.save("large", "가".repeat(100000), "new"));
        assertTrue(store.list().isEmpty());
    }
    @Test void concurrentSaveHasExactlyOneWinner() throws Exception {
        var store = new ScriptStore(directory); var initial = store.save("test", "original", "new");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0, 8).<Callable<Boolean>>mapToObj(i -> () -> {
                try { store.save("test", "version " + i, initial.revision()); return true; }
                catch (ScriptStore.ConflictException e) { return false; }
            }).toList();
            int winners = 0; for (Future<Boolean> result : executor.invokeAll(jobs)) if (result.get()) winners++;
            assertEquals(1, winners);
        }
    }
}
