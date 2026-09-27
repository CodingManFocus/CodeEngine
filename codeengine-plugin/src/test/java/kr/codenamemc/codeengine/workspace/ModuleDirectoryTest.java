package kr.codenamemc.codeengine.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModuleDirectoryTest {
    @TempDir Path directory;

    @Test void createsModuleDirectoryOnFreshInstall() throws Exception {
        Path modules = ModuleDirectory.prepare(directory.resolve("new-install"));
        assertTrue(Files.isDirectory(modules));
        assertEquals("modules", modules.getFileName().toString());
        assertEquals(modules, ModuleDirectory.prepare(directory.resolve("new-install")));
    }

    @Test void migratesAllContentsAndKeepsModuleReadableAfterRestart() throws Exception {
        Path legacy = Files.createDirectory(directory.resolve("scripts"));
        String source = "module welcome;\n// 환영합니다\n";
        Files.writeString(legacy.resolve("welcome.ce"), source);
        Files.createDirectory(legacy.resolve("notes"));
        Files.writeString(legacy.resolve("notes/backup.txt"), "keep me");

        Path modules = ModuleDirectory.prepare(directory);
        assertFalse(Files.exists(legacy));
        assertEquals(source, new ModuleSourceStore(modules).read("welcome").source());
        assertEquals("keep me", Files.readString(modules.resolve("notes/backup.txt")));
        assertEquals(modules, ModuleDirectory.prepare(directory));
    }

    @Test void refusesAmbiguousDirectoriesWithoutChangingEitherCopy() throws Exception {
        Path legacy = Files.createDirectory(directory.resolve("scripts"));
        Path modules = Files.createDirectory(directory.resolve("modules"));
        Files.writeString(legacy.resolve("hello.ce"), "legacy contents");
        Files.writeString(modules.resolve("hello.ce"), "current contents");

        IOException error = assertThrows(IOException.class, () -> ModuleDirectory.prepare(directory));
        assertTrue(error.getMessage().contains("Both module source directories exist"));
        assertEquals("legacy contents", Files.readString(legacy.resolve("hello.ce")));
        assertEquals("current contents", Files.readString(modules.resolve("hello.ce")));
    }

    @Test void rejectsLegacySymlinkWithoutMovingItsTarget() throws Exception {
        Path target = Files.createDirectory(directory.resolve("target"));
        Files.writeString(target.resolve("hello.ce"), "untouched");
        Files.createSymbolicLink(directory.resolve("scripts"), target);
        assertThrows(IOException.class, () -> ModuleDirectory.prepare(directory));
        assertEquals("untouched", Files.readString(target.resolve("hello.ce")));
        assertFalse(Files.exists(directory.resolve("modules")));
    }

    @Test void rejectsDestinationSymlinkWithoutMovingLegacyDirectory() throws Exception {
        Path target = Files.createDirectory(directory.resolve("target"));
        Files.createSymbolicLink(directory.resolve("modules"), target);
        Path legacy = Files.createDirectory(directory.resolve("scripts"));
        Files.writeString(legacy.resolve("hello.ce"), "untouched");
        assertThrows(IOException.class, () -> ModuleDirectory.prepare(directory));
        assertEquals("untouched", Files.readString(legacy.resolve("hello.ce")));
    }

    @Test void rejectsLegacyFileWithoutRenamingIt() throws Exception {
        Path legacy = Files.writeString(directory.resolve("scripts"), "untouched");
        assertThrows(IOException.class, () -> ModuleDirectory.prepare(directory));
        assertEquals("untouched", Files.readString(legacy));
        assertFalse(Files.exists(directory.resolve("modules")));
    }
}
