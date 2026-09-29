package kr.codenamemc.codeengine.workspace;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ModuleSourceStoreTest {
    @TempDir Path directory;
    @Test void atomicSaveDetectsStaleEditors() throws Exception {
        var store = new ModuleSourceStore(directory);
        var original = store.save("hello", "module hello;", "new");
        var updated = store.save("hello", "module hello;\n", original.revision());
        assertNotEquals(original.revision(), updated.revision());
        assertThrows(ModuleSourceStore.ConflictException.class, () -> store.save("hello", "lost data", original.revision()));
        assertEquals(updated, store.read("hello"));
        assertThrows(ModuleSourceStore.ConflictException.class, () -> store.save("hello", "", "new"));
    }
    @ParameterizedTest @ValueSource(strings = {"../x", "x/../hello", "/tmp/x", "a\\b", ".", "..", "UPPER", "a-b", "a.ce"})
    void rejectsTraversalAndAmbiguousNames(String id) throws Exception {
        var store = new ModuleSourceStore(directory); assertThrows(IllegalArgumentException.class, () -> store.read(id));
    }
    @Test void rejectsSymlinkReadsAndWrites() throws Exception {
        var store = new ModuleSourceStore(directory);
        Path target = Files.createTempFile(directory, "target", ".txt"); Files.writeString(target, "untouched");
        Files.createSymbolicLink(directory.resolve("linked.ce"), target);
        assertThrows(java.io.IOException.class, () -> store.read("linked"));
        assertThrows(java.io.IOException.class, () -> store.save("linked", "bad", "new"));
        assertEquals("untouched", Files.readString(target)); assertTrue(store.list().isEmpty());
    }
    @Test void enforcesUtf8ByteLimit() throws Exception {
        var store = new ModuleSourceStore(directory);
        assertThrows(java.io.IOException.class, () -> store.save("large", "가".repeat(100000), "new"));
        assertTrue(store.list().isEmpty());
    }
    @Test void renameUpdatesOnlyHeaderAndPreservesOriginalOnConflict() throws Exception {
        var store = new ModuleSourceStore(directory);
        var original = store.save("hello", "// module hello;\n/* header */ module /* gap */ hello ;\nstate String text = \"module hello;\";", "new");
        var renamed = store.rename("hello", "welcome", original.revision());
        assertEquals("// module hello;\n/* header */ module /* gap */ welcome ;\nstate String text = \"module hello;\";", renamed.source());
        assertEquals(renamed, store.read("welcome"));
        assertFalse(store.list().contains("hello"));
        assertThrows(ModuleSourceStore.ConflictException.class, () -> store.delete("welcome", original.revision()));
        store.save("taken", "module taken;", "new");
        assertThrows(ModuleSourceStore.ConflictException.class, () -> store.rename("welcome", "taken", renamed.revision()));
        assertEquals(renamed, store.read("welcome"));
        assertThrows(ModuleSourceStore.ConflictException.class, () -> store.rename("welcome", "other", original.revision()));
        assertFalse(store.list().contains("other"));
    }
    @Test void concurrentSaveHasExactlyOneWinner() throws Exception {
        var store = new ModuleSourceStore(directory); var initial = store.save("test", "original", "new");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0, 8).<Callable<Boolean>>mapToObj(i -> () -> {
                try { store.save("test", "version " + i, initial.revision()); return true; }
                catch (ModuleSourceStore.ConflictException e) { return false; }
            }).toList();
            int winners = 0; for (Future<Boolean> result : executor.invokeAll(jobs)) if (result.get()) winners++;
            assertEquals(1, winners);
        }
    }
}
