package kr.codenamemc.codeengine.runtime.dependency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;

/** Class names only: indexing never initializes or defines a provider class. */
final class ClasspathIndex {
    private ClasspathIndex() { }

    static Set<String> read(Path path) throws IOException {
        Set<String> classes = new HashSet<>();
        if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                files.filter(Files::isRegularFile).forEach(file ->
                    add(classes, path.relativize(file).toString().replace('\\', '/')));
            }
        } else {
            try (JarFile jar = new JarFile(path.toFile())) {
                // Conservative collision detection also covers classes supplied only by
                // a versioned base library. Provider JARs themselves cannot be multi-release.
                jar.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> add(classes, entry.getName()));
            }
        }
        return classes;
    }

    private static void add(Set<String> classes, String name) {
        if (name.startsWith("META-INF/versions/")) {
            int versionEnd = name.indexOf('/', "META-INF/versions/".length());
            if (versionEnd < 0) return;
            name = name.substring(versionEnd + 1);
        }
        if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class")) return;
        classes.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
    }
}
