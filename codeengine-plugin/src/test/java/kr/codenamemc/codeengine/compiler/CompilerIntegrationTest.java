package kr.codenamemc.codeengine.compiler;
import java.nio.file.*;
import java.net.URLClassLoader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.runtime.RuntimeClasspath;

class CompilerIntegrationTest {
    @TempDir Path directory;
    ModuleCompiler compiler() throws Exception {
        return new ModuleCompiler(directory, RuntimeClasspath.collect(CodeModule.class, org.bukkit.Bukkit.class, net.kyori.adventure.text.Component.class));
    }
    @Test void compilesAndLoadsRealBytecode() throws Exception {
        var compiled = compiler().compile("""
            module test;
            state int count = 0;
            on BlockBreakEvent e { if (e.getBlock().getType() == Material.STONE) e.setCancelled(true); }
            fn twice(long n) -> long { return n * 2L; }
            command ctest { sender.sendMessage(Component.text("ok")); return true; }
            every 20 ticks { count++; }
            """, "test");
        assertTrue(Files.size(compiled.jar()) > 0);
        try (var loader = new URLClassLoader(new java.net.URL[]{compiled.jar().toUri().toURL()}, CodeModule.class.getClassLoader())) {
            var type = loader.loadClass(compiled.className()); var instance = type.getConstructor().newInstance();
            assertInstanceOf(CodeModule.class, instance);
            var method = type.getDeclaredMethod("twice", long.class); method.setAccessible(true);
            assertEquals(42L, method.invoke(instance, 21L));
        }
    }
    @Test void javaErrorsMapToOriginalSourceAndDoNotLeavePartialBuilds() throws Exception {
        var error = assertThrows(CompilationException.class, () -> compiler().compile("module bad;\ncommand broken {\n    nonexistent();\n    return true;\n}", "bad"));
        assertTrue(error.getMessage().contains("bad.ce:3:"), error.getMessage());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
    @Test void filenameMustMatchId() throws Exception {
        assertThrows(SourceException.class, () -> compiler().compile("module wrong;", "test"));
    }
    @Test void sameNameBuildsHaveIsolatedArtifacts() throws Exception {
        var a = compiler().compile("module test;", "test"); var b = compiler().compile("module test; state int a = 1;", "test");
        assertNotEquals(a.jar(), b.jar()); assertTrue(Files.exists(a.jar())); assertTrue(Files.exists(b.jar()));
    }
}
