package kr.codenamemc.codeengine.runtime.dependency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderFixtures {
    private ProviderFixtures() { }
    static Path compile(Path temporary, String id, Map<String, String> sources, String classpath) throws IOException {
        Path root = Files.createDirectory(temporary.resolve(id));
        Path classes = Files.createDirectory(root.resolve("classes"));
        List<Path> inputs = new ArrayList<>();
        for (var source : sources.entrySet()) {
            Path file = root.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            inputs.add(file);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var options = List.of("--release", "21", "-proc:none", "-classpath", classpath, "-d", classes.toString());
            assertTrue(compiler.getTask(null, manager, null, options, null,
                manager.getJavaFileObjectsFromPaths(inputs)).call());
        }
        Path jar = root.resolve(id + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return jar;
    }
}
