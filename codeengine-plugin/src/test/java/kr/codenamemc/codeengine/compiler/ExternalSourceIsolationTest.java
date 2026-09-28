package kr.codenamemc.codeengine.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.runtime.RuntimeClasspath;
import static org.junit.jupiter.api.Assertions.*;

class ExternalSourceIsolationTest {
    @TempDir Path directory;

    @Test void dependencySourcesCannotBeCompiledIntoTheModuleOrUsedAsItsApi() throws Exception {
        Path dependency = sourceOnlyJar();
        String baseClasspath = RuntimeClasspath.collect(CodeModule.class, org.bukkit.Bukkit.class,
            net.kyori.adventure.text.Component.class);
        Path builds = directory.resolve("builds");
        ModuleCompiler compiler = new ModuleCompiler(builds, baseClasspath);
        CompilationException error = assertThrows(CompilationException.class, () -> compiler.compile("""
            module source_isolation;
            use external.SourceOnlyApi;
            enable { int result = SourceOnlyApi.value(); }
            """, "source_isolation", dependency.toString()));
        assertTrue(error.getMessage().contains("external"), error.getMessage());
        try (var files = Files.list(builds)) { assertEquals(0, files.count()); }
    }

    private Path sourceOnlyJar() throws IOException {
        Path jar = directory.resolve("external-sources.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("external/SourceOnlyApi.java"));
            output.write("package external; public final class SourceOnlyApi { public static int value() { return 7; } }"
                .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}
