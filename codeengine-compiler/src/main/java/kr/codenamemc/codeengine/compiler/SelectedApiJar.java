package kr.codenamemc.codeengine.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Compile-time view only. Never packaged into the module or used to define runtime API classes. */
final class SelectedApiJar {
    private SelectedApiJar() { }

    static Path write(Path directory, Map<String, Path> selectedClasses) throws IOException {
        if (selectedClasses.isEmpty()) return null;
        Map<Path, List<String>> byJar = new LinkedHashMap<>();
        selectedClasses.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            byJar.computeIfAbsent(entry.getValue(), ignored -> new java.util.ArrayList<>()).add(entry.getKey()));
        Path output = directory.resolve("selected-api.jar");
        try (JarOutputStream target = new JarOutputStream(Files.newOutputStream(output))) {
            for (var group : byJar.entrySet()) {
                try (JarFile source = new JarFile(group.getKey().toFile())) {
                    for (String binaryName : group.getValue()) {
                        String name = binaryName.replace('.', '/') + ".class";
                        JarEntry original = source.getJarEntry(name);
                        if (original == null) throw new IOException("Selected API class disappeared: " + binaryName);
                        JarEntry entry = new JarEntry(name);
                        entry.setTime(0);
                        target.putNextEntry(entry);
                        try (var input = source.getInputStream(original)) { input.transferTo(target); }
                        target.closeEntry();
                    }
                }
            }
        }
        return output;
    }
}
