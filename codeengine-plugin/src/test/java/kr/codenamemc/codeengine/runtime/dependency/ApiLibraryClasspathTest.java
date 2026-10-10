package kr.codenamemc.codeengine.runtime.dependency;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.compiler.*;
import kr.codenamemc.codeengine.runtime.RuntimeClasspath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ApiLibraryClasspathTest {
    @TempDir Path temporary;
    private static final ClassLoader parent = CodeModule.class.getClassLoader();

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedLibraryDefinitionsKeepIdentityAndStateWithSharedOrSeparateLoaders(boolean separate) throws Exception {
        Path library = jar("library", Map.of(
            "library.Value", """
                package library; public class Value<T extends Leaf> {
                    static { System.setProperty("codeengine.libraryInitialized", "true"); }
                    public static int calls;
                    public T[] leaves;
                    public int increment() { return ++calls; }
                }
                """,
            "library.Leaf", "package library; public class Leaf {}",
            "library.Hidden", "package library; public class Hidden { public static void call() {} }"), "");
        Path api = jar("api", Map.of("fixture.Api", """
            package fixture; public class Api {
                public static library.Value<library.Leaf> value() { return new library.Value<>(); }
            }
            """), library.toString());
        System.clearProperty("codeengine.libraryInitialized");
        try (var libraries = loader(parent, library);
             var provider = separate ? loader(libraries, api) : loader(parent, api, library)) {
            var ast = new Parser("""
                module libraries;
                use fixture.Api from "Provider";
                use library.Value;
                enable {
                    Value<library.Leaf> value = Api.value();
                    if (value.increment() != 1) throw new AssertionError("wrong library state");
                }
                """).parse();
            var resolved = resolve(ast, List.of(provider("Provider", api, provider)));
            assertEquals(library.toRealPath(), resolved.selectedClasses().get("library.Value"));
            assertTrue(resolved.selectedClasses().containsKey("library.Leaf"), "generic bounds must be included");
            assertFalse(resolved.selectedClasses().containsKey("library.Hidden"));
            var compiled = compiler().compile(ast, ast.id(), resolved.selectedClasses());
            assertNull(System.getProperty("codeengine.libraryInitialized"));
            try (var module = resolved.newLoader(compiled.jar(), parent);
                 var generated = new JarFile(compiled.jar().toFile())) {
                Class<?> value = provider.loadClass("library.Value");
                assertSame(value, module.loadClass("library.Value"));
                assertNull(generated.getEntry("library/Value.class"));
                enable(module, compiled);
                assertEquals(1, value.getField("calls").getInt(null));
                assertThrows(ClassNotFoundException.class, () -> module.loadClass("library.Hidden"));
            }
            assertSame(separate ? libraries : provider, provider.loadClass("library.Hidden").getClassLoader(), "closing a module must leave libraries open");
            var hidden = new Parser("module hidden; use fixture.Api from \"Provider\"; enable { library.Hidden.call(); }").parse();
            assertThrows(CompilationException.class, () -> compiler().compile(hidden, hidden.id(), resolved.selectedClasses()));
        } finally { System.clearProperty("codeengine.libraryInitialized"); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void duplicateLibraryNamesRequireTheSameClassIdentityEvenWithTheSameSourceJar(boolean sameJar) throws Exception {
        Path firstLibrary = jar("firstLibrary", Map.of("library.Value", "package library; public class Value {}"), "");
        Path secondLibrary = sameJar ? firstLibrary : jar("secondLibrary", Map.of("library.Value", "package library; public class Value {}"), "");
        Path first = api("first", "first.Api", firstLibrary);
        Path second = api("second", "second.Api", secondLibrary);
        var ast = new Parser("module duplicate; use first.Api from \"First\"; use second.Api from \"Second\";").parse();
        try (var aLibrary = loader(parent, firstLibrary); var bLibrary = loader(parent, secondLibrary);
             var a = loader(aLibrary, first); var b = loader(bLibrary, second)) {
            IOException error = assertThrows(IOException.class, () -> resolve(ast,
                List.of(provider("First", first, a), provider("Second", second, b))));
            assertTrue(error.getMessage().contains("Conflicting API type library.Value"), error.getMessage());
        }
        try (var shared = loader(parent, firstLibrary); var a = loader(shared, first); var b = loader(shared, second)) {
            var resolved = resolve(ast, List.of(provider("First", first, a), provider("Second", second, b)));
            assertEquals(firstLibrary.toRealPath(), resolved.selectedClasses().get("library.Value"));
        }
    }

    @Test void otherPluginSignaturesStillRequireAnExplicitDependency() throws Exception {
        Path dependency = jar("dependency", Map.of("library.Value", "package library; public class Value {}"), "");
        Path api = api("api", "fixture.Api", dependency);
        var ast = new Parser("module dependent; use fixture.Api from \"Provider\";").parse();
        try (var other = loader(parent, dependency); var provider = loader(other, api)) {
            var declared = provider("Provider", api, provider);
            var otherPlugin = new DependencyClasspath.PluginOrigin("Other", dependency, other);
            var inventory = List.of(new DependencyClasspath.PluginOrigin("Provider", api, provider), otherPlugin);
            IOException error = assertThrows(IOException.class, () -> DependencyClasspath.prepare(base(), List.of(declared),
                inventory, ast.imports(), parent));
            assertTrue(error.getMessage().contains("requires plugin \"Other\""), error.getMessage());
            var resolved = DependencyClasspath.prepare(base(), List.of(declared, provider("Other", dependency, other)),
                inventory, ast.imports(), parent);
            assertEquals(dependency.toRealPath(), resolved.selectedClasses().get("library.Value"));
            // Missing metadata cannot make a known plugin loader look like a library loader.
            assertThrows(IOException.class, () -> DependencyClasspath.prepare(base(), List.of(declared),
                List.of(new DependencyClasspath.PluginOrigin("Other", null, other)), ast.imports(), parent));
        }
    }

    @Test void pluginDescriptorsCannotMasqueradeAsLibraryJars() throws Exception {
        Path dependency = jar("dependency", Map.of("library.Value", "package library; public class Value {}"), "");
        Path pluginJar = temporary.resolve("undeclared.jar");
        try (var source = new JarFile(dependency.toFile()); var output = new JarOutputStream(Files.newOutputStream(pluginJar))) {
            copy(source, "library/Value.class", output, "library/Value.class");
            output.putNextEntry(new JarEntry("paper-plugin.yml"));
            output.write("name: Other\nmain: library.Value\nversion: 1.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path api = api("api", "fixture.Api", pluginJar);
        try (var other = loader(parent, pluginJar); var provider = loader(other, api)) {
            var ast = new Parser("module dependent; use fixture.Api from \"Provider\";").parse();
            IOException error = assertThrows(IOException.class, () -> resolve(ast, List.of(provider("Provider", api, provider))));
            assertTrue(error.getMessage().contains("undeclared plugin JAR"), error.getMessage());
        }
    }

    @Test void typesWithoutAnIdentifiableSourceAreRejected() throws Exception {
        Path dependency = jar("dependency", Map.of("library.Value", "package library; public class Value {}"), "");
        byte[] bytes;
        try (var jar = new JarFile(dependency.toFile()); var input = jar.getInputStream(jar.getJarEntry("library/Value.class"))) {
            bytes = input.readAllBytes();
        }
        ClassLoader noSource = new ClassLoader(parent) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals("library.Value")) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Path api = api("api", "fixture.Api", dependency);
        try (var provider = loader(noSource, api)) {
            var ast = new Parser("module dependent; use fixture.Api from \"Provider\";").parse();
            IOException error = assertThrows(IOException.class, () -> resolve(ast, List.of(provider("Provider", api, provider))));
            assertTrue(error.getMessage().contains("no local source JAR: library.Value"), error.getMessage());
        }
    }

    @Test void multiReleaseLibraryUsesTheSameDefinitionInJavacAndAtRuntime() throws Exception {
        Path original = jar("original", Map.of("library.Value", "package library; public class Value {}"), "");
        Path versioned = jar("versioned", Map.of("library.Value", "package library; public class Value { public String onlyOn21() { return \"21\"; } }"), "");
        Path library = temporary.resolve("multi-release.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var base = new JarFile(original.toFile()); var selected = new JarFile(versioned.toFile());
             var output = new JarOutputStream(Files.newOutputStream(library), manifest)) {
            copy(base, "library/Value.class", output, "library/Value.class");
            copy(selected, "library/Value.class", output, "META-INF/versions/21/library/Value.class");
        }
        Path api = api("api", "fixture.Api", versioned);
        try (var libraries = loader(parent, library); var provider = loader(libraries, api)) {
            var ast = new Parser("""
                module multirelease;
                use fixture.Api from "Provider";
                enable {
                    if (!new library.Value().onlyOn21().equals("21")) throw new AssertionError("wrong version");
                }
                """).parse();
            var resolved = resolve(ast, List.of(provider("Provider", api, provider)));
            var compiled = compiler().compile(ast, ast.id(), resolved.selectedClasses());
            try (var module = resolved.newLoader(compiled.jar(), parent)) {
                assertSame(libraries.loadClass("library.Value"), module.loadClass("library.Value"));
                enable(module, compiled);
            }
        }
    }

    @Test void libraryCannotReplaceABaseClasspathClass() throws Exception {
        Path base = jar("base", Map.of("library.Value", "package library; public class Value {}"), "");
        Path copy = jar("copy", Map.of("library.Value", "package library; public class Value {}"), "");
        Path api = api("api", "fixture.Api", copy);
        try (var libraries = loader(parent, copy); var provider = loader(libraries, api)) {
            var ast = new Parser("module duplicate; use fixture.Api from \"Provider\";").parse();
            IOException error = assertThrows(IOException.class, () -> DependencyClasspath.prepare(
                base() + java.io.File.pathSeparator + base, List.of(provider("Provider", api, provider)), ast.imports(), parent));
            assertTrue(error.getMessage().contains("duplicates a server/engine class: library.Value"), error.getMessage());
        }
    }

    @Test void manifestClasspathLibraryFailsWithAClearDiagnostic() throws Exception {
        Path original = jar("original", Map.of("library.Value", "package library; public class Value {}"), "");
        Path library = temporary.resolve("manifest-classpath.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "other.jar");
        try (var source = new JarFile(original.toFile()); var output = new JarOutputStream(Files.newOutputStream(library), manifest)) {
            copy(source, "library/Value.class", output, "library/Value.class");
        }
        Path api = api("api", "fixture.Api", library);
        try (var libraries = loader(parent, library); var provider = loader(libraries, api)) {
            var ast = new Parser("module unsupported; use fixture.Api from \"Provider\";").parse();
            IOException error = assertThrows(IOException.class, () -> resolve(ast, List.of(provider("Provider", api, provider))));
            assertTrue(error.getMessage().contains("API library JARs with manifest Class-Path"), error.getMessage());
        }
    }

    private static void copy(JarFile source, String name, JarOutputStream output, String target) throws IOException {
        output.putNextEntry(new JarEntry(target));
        try (var input = source.getInputStream(source.getJarEntry(name))) { input.transferTo(output); }
        output.closeEntry();
    }
    private static void enable(ModuleClassLoader loader, CompiledModule compiled) throws Exception {
        CodeModule module = loader.loadClass(compiled.className()).asSubclass(CodeModule.class).getConstructor().newInstance();
        module.prepare(null);
        module.enable();
    }
    private Path api(String id, String name, Path library) throws IOException {
        int dot = name.lastIndexOf('.');
        return jar(id, Map.of(name, "package " + name.substring(0, dot) + "; public class " + name.substring(dot + 1)
            + " { public library.Value value() { return null; } }"), library.toString());
    }
    private Path jar(String name, Map<String, String> sources, String classpath) throws IOException {
        return ProviderFixtures.compile(temporary, name, sources, classpath);
    }
    private static URLClassLoader loader(ClassLoader parent, Path... jars) throws IOException {
        var urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return new URLClassLoader(urls, parent);
    }
    private static DependencyClasspath.Provider provider(String name, Path jar, ClassLoader loader) {
        return new DependencyClasspath.Provider(name, jar, loader);
    }
    private String base() throws Exception {
        return RuntimeClasspath.collect(CodeModule.class, org.bukkit.Bukkit.class, net.kyori.adventure.text.Component.class);
    }
    private ModuleCompiler compiler() throws Exception { return new ModuleCompiler(temporary.resolve("builds"), base()); }
    private ResolvedClasspath resolve(ModuleAst ast, List<DependencyClasspath.Provider> providers) throws Exception {
        return DependencyClasspath.prepare(base(), providers, ast.imports(), parent);
    }
}
