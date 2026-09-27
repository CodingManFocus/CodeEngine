package kr.codenamemc.codeengine.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;

/** Each build uses a fresh immutable directory; failed builds never overwrite live JARs. */
public final class ModuleCompiler {
    private final Path buildRoot;
    private final String classpath;
    public ModuleCompiler(Path buildRoot, String classpath) {
        this.buildRoot = buildRoot; this.classpath = classpath;
    }
    public CompiledModule compile(String source, String expectedId) throws IOException, CompilationException {
        ModuleAst ast = new Parser(source).parse();
        if (!ast.id().equals(expectedId)) throw new SourceException(1, "Module id must match file name: " + expectedId);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("A full JDK 21+ is required (jdk.compiler is missing)");
        GeneratedSource generated = new JavaEmitter().emit(ast);
        Files.createDirectories(buildRoot);
        Path directory = Files.createTempDirectory(buildRoot, ast.id() + "-");
        boolean success = false;
        try {
            Path input = directory.resolve("Entry.java"), classes = directory.resolve("classes");
            Files.createDirectories(classes);
            Files.writeString(input, generated.source(), StandardCharsets.UTF_8);
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                var units = manager.getJavaFileObjects(input.toFile());
                var options = List.of("--release", "21", "-encoding", "UTF-8", "-proc:none", "-g", "-classpath", classpath, "-d", classes.toString());
                boolean compiled = compiler.getTask(null, manager, diagnostics, options, null, units).call();
                if (!compiled) throw new CompilationException(diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> expectedId + ".ce:" + generated.sourceLine(d.getLineNumber()) + ": " + d.getMessage(Locale.ROOT)).toList());
            }
            Path jar = directory.resolve("module.jar");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar)); var walk = Files.walk(classes)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    JarEntry entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                    entry.setTime(0); output.putNextEntry(entry); Files.copy(file, output); output.closeEntry();
                }
            }
            success = true;
            return new CompiledModule(ast.id(), generated.className(), jar, input);
        } finally { if (!success) deleteBuild(directory); }
    }
    public static void deleteBuild(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
