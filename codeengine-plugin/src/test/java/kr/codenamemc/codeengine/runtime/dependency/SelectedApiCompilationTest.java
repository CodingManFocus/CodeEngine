package kr.codenamemc.codeengine.runtime.dependency;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.compiler.*;
import kr.codenamemc.codeengine.runtime.RuntimeClasspath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SelectedApiCompilationTest {
    @TempDir Path temporary;
    private static final ClassLoader engineParent = CodeModule.class.getClassLoader();

    @Test void selectedProviderControlsJavacAndRuntimeRegardlessOfJarOrder() throws Exception {
        Path first = jar("first", Map.of(
            "shared.Api", "package shared; public class Api { public static int firstOnly() { return 1; } }",
            "first.Marker", "package first; public class Marker { public static int value() { return shaded.Unused.first(); } }",
            "shaded.Unused", "package shaded; public class Unused { public static int first() { return 7; } }"));
        Path second = jar("second", Map.of(
            "shared.Api", """
                package shared; public class Api {
                    static { System.setProperty("codeengine.selectedApiInitialized", "true"); }
                    public static String secondOnly() { return shaded.Unused.second(); }
                }
                """,
            "shaded.Unused", "package shaded; public class Unused { public static String second() { return \"second\"; } }"));
        System.clearProperty("codeengine.selectedApiInitialized");
        try (URLClassLoader a = loader(first); URLClassLoader b = loader(second)) {
            var providers = List.of(provider("First", first, a), provider("Second", second, b));
            var ast = new Parser("""
                module selected;
                use first.Marker from "First";
                use shared.Api from "Second";
                enable {
                    if (Marker.value() != 7 || !Api.secondOnly().equals("second")) throw new AssertionError("wrong provider");
                }
                """).parse();
            var resolved = resolve(ast, providers);
            assertEquals(second.toRealPath(), resolved.selectedClasses().get("shared.Api"));
            assertFalse(resolved.selectedClasses().containsKey("shaded.Unused"));
            var compiled = compiler().compile(ast, ast.id(), resolved.selectedClasses());
            assertNull(System.getProperty("codeengine.selectedApiInitialized"), "signature inspection/compilation must not initialize API");
            try (ModuleClassLoader module = resolved.newLoader(compiled.jar(), engineParent);
                 JarFile generated = new JarFile(compiled.jar().toFile())) {
                assertNull(generated.getEntry("shared/Api.class"), "selected API view must not be packaged into module");
                assertSame(b.loadClass("shared.Api"), module.loadClass("shared.Api"));
                CodeModule instance = module.loadClass(compiled.className()).asSubclass(CodeModule.class).getConstructor().newInstance();
                instance.prepare(null);
                instance.enable();
                assertEquals("true", System.getProperty("codeengine.selectedApiInitialized"));
            }
            var wrong = new Parser("""
                module wrong;
                use shared.Api from "First";
                enable { Api.secondOnly(); }
                """).parse();
            var selectedFirst = resolve(wrong, providers);
            assertThrows(CompilationException.class, () -> compiler().compile(wrong, wrong.id(), selectedFirst.selectedClasses()));
        } finally { System.clearProperty("codeengine.selectedApiInitialized"); }
    }

    @Test void publicGenericReturnTypesCannotSilentlyMixDuplicateClasses() throws Exception {
        Path first = jar("genericFirst", Map.of(
            "first.Api", "package first; public class Api { public static java.util.List<shared.Value> values() { return null; } }",
            "shared.Value", "package shared; public class Value {}"));
        Path second = jar("genericSecond", Map.of(
            "second.Api", "package second; public class Api { public static void accept(shared.Value[] value) {} }",
            "shared.Value", "package shared; public class Value {}"));
        try (URLClassLoader a = loader(first); URLClassLoader b = loader(second)) {
            var ast = new Parser("module conflict; use first.Api from \"First\"; use second.Api from \"Second\";").parse();
            IOException failure = assertThrows(IOException.class, () -> resolve(ast, List.of(provider("First", first, a), provider("Second", second, b))));
            assertTrue(failure.getMessage().contains("Conflicting API type shared.Value"), failure.getMessage());
        }
    }

    @Test void nestedImportsAndGenericBoundsAreIncluded() throws Exception {
        Path jar = jar("nested", Map.of(
            "fixture.Outer", """
                package fixture; public class Outer {
                    public static class Inner<T extends Value> {
                        public java.util.List<? extends T[]> values() { return null; }
                    }
                }
                """,
            "fixture.Value", "package fixture; public class Value {}"));
        try (URLClassLoader loader = loader(jar)) {
            var ast = new Parser("module nested; use fixture.Outer.Inner from \"Provider\"; enable { var value = new Inner<>(); }").parse();
            var selected = resolve(ast, List.of(provider("Provider", jar, loader)));
            assertTrue(selected.selectedClasses().keySet().containsAll(List.of("fixture.Outer", "fixture.Outer$Inner", "fixture.Value")));
            compiler().compile(ast, ast.id(), selected.selectedClasses());
        }
    }

    @Test void importMustExistInItsNamedProviderAndExternalImportsRequireFrom() throws Exception {
        Path jar = jar("missing", Map.of("fixture.Api", "package fixture; public class Api {}"));
        try (URLClassLoader loader = loader(jar)) {
            var providers = List.of(provider("Provider", jar, loader));
            var missing = new Parser("module missing; use fixture.Missing from \"Provider\";").parse();
            assertThrows(SourceException.class, () -> resolve(missing, providers));
            var unqualified = new Parser("module missing; requires plugin \"Provider\"; use fixture.Api;").parse();
            assertThrows(SourceException.class, () -> resolve(unqualified, providers));
        }
    }

    @Test void requiresAloneDoesNotExposePluginClasses() throws Exception {
        Path jar = jar("hidden", Map.of("fixture.Api", "package fixture; public class Api { public static void call() {} }"));
        try (URLClassLoader loader = loader(jar)) {
            var ast = new Parser("module hidden; requires plugin \"Provider\"; enable { fixture.Api.call(); }").parse();
            var resolved = resolve(ast, List.of(provider("Provider", jar, loader)));
            assertTrue(resolved.selectedClasses().isEmpty());
            assertThrows(CompilationException.class, () -> compiler().compile(ast, ast.id(), resolved.selectedClasses()));
        }
    }

    private Path jar(String name, Map<String, String> sources) throws IOException {
        return ProviderFixtures.compile(temporary, name, sources, "");
    }
    private static URLClassLoader loader(Path jar) throws IOException {
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, engineParent);
    }
    private static DependencyClasspath.Provider provider(String name, Path jar, ClassLoader loader) {
        return new DependencyClasspath.Provider(name, jar, loader);
    }
    private String baseClasspath() throws Exception {
        return RuntimeClasspath.collect(CodeModule.class, org.bukkit.Bukkit.class, net.kyori.adventure.text.Component.class);
    }
    private ModuleCompiler compiler() throws Exception { return new ModuleCompiler(temporary.resolve("builds"), baseClasspath()); }
    private ResolvedClasspath resolve(ModuleAst ast, List<DependencyClasspath.Provider> providers) throws Exception {
        return DependencyClasspath.prepare(baseClasspath(), providers, ast.imports(), engineParent);
    }
}
