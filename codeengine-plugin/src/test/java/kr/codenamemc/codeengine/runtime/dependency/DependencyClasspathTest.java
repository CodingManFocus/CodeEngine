package kr.codenamemc.codeengine.runtime.dependency;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import kr.codenamemc.codeengine.compiler.ModuleAst;
import kr.codenamemc.codeengine.compiler.SourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DependencyClasspathTest {
    @TempDir Path temporary;

    @Test void nativeCallsUseTheExactProviderClassAndItsState() throws Exception {
        Path providerJar = compile("provider", Map.of("fixture.Api", """
            package fixture;
            public final class Api {
                private static int value;
                public static int add(int amount) { return value += amount; }
            }
            """), "");
        Path moduleJar = compile("module", Map.of("module.Entry", """
            package module;
            public final class Entry {
                public static Class<?> type() { return fixture.Api.class; }
                public static int call() { return fixture.Api.add(7); }
            }
            """), providerJar.toString());
        try (URLClassLoader provider = loader(providerJar)) {
            var resolved = prepare(providerJar, provider);
            assertEquals(providerJar.toRealPath(), resolved.selectedClasses().get("fixture.Api"));
            try (ModuleClassLoader module = resolved.newLoader(moduleJar, ClassLoader.getPlatformClassLoader())) {
                assertArrayEquals(new URL[]{moduleJar.toRealPath().toUri().toURL()}, module.getURLs());
                Class<?> entry = module.loadClass("module.Entry");
                Class<?> api = provider.loadClass("fixture.Api");
                assertSame(api, entry.getMethod("type").invoke(null));
                assertEquals(7, entry.getMethod("call").invoke(null));
                assertEquals(10, api.getMethod("add", int.class).invoke(null, 3));
            }
        }
    }

    @Test void constructorsFieldsOverloadsGenericsAndCheckedExceptionsUseJavaSemantics() throws Exception {
        Path providerJar = compile("javaApi", Map.of("fixture.Api", """
            package fixture;
            public class Api {
                public static final String NAME = "api";
                public int value;
                public Api(int value) { this.value = value; }
                public String select(int value) { return "int"; }
                public String select(String value) { return "string"; }
                public java.util.List<String> values() { return java.util.List.of(NAME); }
                public void fail() throws java.io.IOException { throw new java.io.IOException("expected"); }
            }
            """), "");
        Path moduleJar = compile("javaModule", Map.of("module.Entry", """
            package module;
            public final class Entry {
                public static String call() {
                    var api = new fixture.Api(7);
                    api.value++;
                    String failure = "missing";
                    try { api.fail(); } catch (java.io.IOException expected) { failure = expected.getMessage(); }
                    return api.value + ":" + api.select(1) + ":" + api.select("x") + ":" + api.values().getFirst() + ":" + failure;
                }
            }
            """), providerJar.toString());
        try (URLClassLoader provider = loader(providerJar);
             ModuleClassLoader module = prepare(providerJar, provider).newLoader(moduleJar, ClassLoader.getPlatformClassLoader())) {
            assertEquals("8:int:string:api:expected", module.loadClass("module.Entry").getMethod("call").invoke(null));
        }
    }

    @Test void duplicateProviderBinaryNamesFailBeforeLoading() throws Exception {
        Path first = compile("first", Map.of("fixture.Api", "package fixture; public class Api {}"), "");
        Path second = compile("second", Map.of("fixture.Api", "package fixture; public class Api {}"), "");
        try (URLClassLoader firstLoader = loader(first); URLClassLoader secondLoader = loader(second)) {
            SourceException error = assertThrows(SourceException.class, () -> DependencyClasspath.prepare("", List.of(
                new DependencyClasspath.Provider("First", first, firstLoader),
                new DependencyClasspath.Provider("Second", second, secondLoader)),
                    List.of(imported("fixture.Api", "First"), imported("fixture.Api", "Second")), ClassLoader.getPlatformClassLoader()));
            assertTrue(error.getMessage().contains("Conflicting API type fixture.Api"));
        }
    }

    @Test void providerCannotReplaceABaseClasspathClass() throws Exception {
        Path base = compile("base", Map.of("fixture.Api", "package fixture; public class Api {}"), "");
        Path providerJar = compile("provider", Map.of("fixture.Api", "package fixture; public class Api {}"), "");
        try (URLClassLoader provider = loader(providerJar)) {
            SourceException error = assertThrows(SourceException.class, () -> DependencyClasspath.prepare(base.toString(), List.of(
                new DependencyClasspath.Provider("Provider", providerJar, provider)),
                List.of(imported("fixture.Api", "Provider")), ClassLoader.getPlatformClassLoader()));
            assertTrue(error.getMessage().contains("duplicates a server/engine class: fixture.Api"));
        }
    }

    @Test void globallyVisibleUndeclaredPluginClassesAreRejected() throws Exception {
        Path base = compile("base", Map.of("base.Visible", "package base; public class Visible {}"), "");
        Path unrelated = compile("unrelated", Map.of("unrelated.Hidden", "package unrelated; public class Hidden {}"), "");
        Path moduleJar = compile("module", Map.of("module.Entry", "package module; public class Entry {}"), "");
        try (URLClassLoader parent = new URLClassLoader(new URL[]{base.toUri().toURL(), unrelated.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            var resolved = DependencyClasspath.prepare(base.toString(), List.of(), List.of(), parent);
            try (ModuleClassLoader module = resolved.newLoader(moduleJar, parent)) {
                assertSame(parent.loadClass("base.Visible"), module.loadClass("base.Visible"));
                assertSame(String.class, module.loadClass("java.lang.String"));
                assertSame(java.sql.Driver.class, module.loadClass("java.sql.Driver"));
                ClassNotFoundException error = assertThrows(ClassNotFoundException.class, () -> module.loadClass("unrelated.Hidden"));
                assertTrue(error.getMessage().contains("Undeclared plugin class"));
                assertThrows(ClassNotFoundException.class, () -> module.loadClass("unrelated.Hidden"));
            }
        }
    }

    @Test void providerLoaderReturningAParentCopyFailsInsteadOfMixingTypes() throws Exception {
        Path parentJar = compile("parent", Map.of("fixture.Api", "package fixture; public class Api {}"), "");
        Path providerJar = compile("provider", Map.of("fixture.Api", "package fixture; public class Api {}"), "");
        Path moduleJar = compile("module", Map.of("module.Entry", "package module; public class Entry {}"), "");
        try (URLClassLoader parent = loader(parentJar);
             URLClassLoader provider = new URLClassLoader(new URL[]{providerJar.toUri().toURL()}, parent)) {
            SourceException error = assertThrows(SourceException.class, () -> prepare(providerJar, provider));
            assertTrue(error.getMessage().contains("different loader or JAR"));
        }
    }

    @Test void lazyOwnClassesLoadWithoutConsultingAStoppedEngineLoader() throws Exception {
        Path moduleJar = compile("module", Map.of("module.Entry", """
            package module;
            public final class Entry {
                public static int later() {
                    return new java.util.function.IntSupplier() {
                        public int getAsInt() { return 7; }
                    }.getAsInt();
                }
            }
            """), "");
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicInteger ownParentLookups = new AtomicInteger();
        ClassLoader engineParent = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("module.")) {
                    ownParentLookups.incrementAndGet();
                    if (stopped.get()) throw new IllegalStateException("engine plugin JAR is closed");
                }
                return super.loadClass(name, resolve);
            }
        };
        var resolved = DependencyClasspath.prepare("", List.of(), List.of(), ClassLoader.getPlatformClassLoader());
        try (ModuleClassLoader module = resolved.newLoader(moduleJar, engineParent)) {
            Class<?> entry = module.loadClass("module.Entry");
            stopped.set(true);
            assertEquals(7, entry.getMethod("later").invoke(null));
            assertSame(module, module.loadClass("module.Entry$1").getClassLoader());
            assertEquals(0, ownParentLookups.get());
        }
    }

    @Test void ownClassesCannotShadowTheServerOrDeclaredProvider() throws Exception {
        Path baseJar = compile("base", Map.of("base.Api", "package base; public class Api {}"), "");
        Path providerJar = compile("provider", Map.of("fixture.Api", "package fixture; public class Api {}"), "");
        Path shadowsBase = compile("baseModule", Map.of("base.Api", "package base; public class Api {}"), "");
        Path shadowsProvider = compile("providerModule", Map.of("fixture.Api", "package fixture; public class Api {}"), "");
        try (URLClassLoader provider = loader(providerJar)) {
            var resolved = DependencyClasspath.prepare(baseJar.toString(), List.of(
                new DependencyClasspath.Provider("Provider", providerJar, provider)),
                List.of(imported("fixture.Api", "Provider")), ClassLoader.getPlatformClassLoader());
            for (Path moduleJar : List.of(shadowsBase, shadowsProvider)) {
                IOException error = assertThrows(IOException.class, () ->
                    resolved.newLoader(moduleJar, ClassLoader.getPlatformClassLoader()));
                assertTrue(error.getMessage().contains("Generated module duplicates"));
            }
        }
    }

    @Test void parallelClassResolutionKeepsProviderIdentityAndClosingModuleKeepsProviderOpen() throws Exception {
        Path providerJar = compile("provider", Map.of(
            "fixture.Api", "package fixture; public class Api {}",
            "fixture.Later", "package fixture; public class Later {}"), "");
        Path moduleJar = compile("module", Map.of("module.Entry", "package module; public class Entry {}"), "");
        try (URLClassLoader provider = loader(providerJar)) {
            var resolved = prepare(providerJar, provider);
            Class<?> api = provider.loadClass("fixture.Api");
            try (ModuleClassLoader module = resolved.newLoader(moduleJar, ClassLoader.getPlatformClassLoader());
                 var executor = Executors.newFixedThreadPool(4)) {
                List<Callable<Class<?>>> calls = new ArrayList<>();
                for (int i = 0; i < 64; i++) calls.add(() -> module.loadClass("fixture.Api"));
                for (var result : executor.invokeAll(calls, 5, TimeUnit.SECONDS)) assertSame(api, result.get());
            }
            assertSame(provider, provider.loadClass("fixture.Later").getClassLoader());
        }
    }

    @Test void unsupportedProviderJarLayoutsFailExplicitly() throws Exception {
        for (String mode : List.of("multiRelease", "manifestClasspath")) {
            Path jar = temporary.resolve(mode + ".jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            if (mode.equals("multiRelease")) manifest.getMainAttributes().putValue("Multi-Release", "true");
            if (mode.equals("manifestClasspath")) manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "other.jar");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
                output.flush();
            }
            try (URLClassLoader provider = loader(jar)) {
                var lifecycleOnly = DependencyClasspath.prepare("", List.of(
                    new DependencyClasspath.Provider("Provider", jar, provider)), List.of(), ClassLoader.getPlatformClassLoader());
                assertTrue(lifecycleOnly.selectedClasses().isEmpty(), mode);
                assertThrows(IOException.class, () -> prepare(jar, provider), mode);
            }
        }
    }

    @Test void unrelatedLifecycleDependencyDoesNotRestrictASelectedApi() throws Exception {
        Path apiJar = compile("api", Map.of("fixture.Api", "package fixture; public class Api {}"), "");
        Path lifecycleJar = temporary.resolve("lifecycle.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(lifecycleJar), manifest)) {
            output.flush();
        }
        try (URLClassLoader api = loader(apiJar); URLClassLoader lifecycle = loader(lifecycleJar)) {
            var resolved = DependencyClasspath.prepare("", List.of(
                new DependencyClasspath.Provider("Api", apiJar, api),
                new DependencyClasspath.Provider("Lifecycle", lifecycleJar, lifecycle)),
                List.of(imported("fixture.Api", "Api")), ClassLoader.getPlatformClassLoader());
            assertEquals(Map.of("fixture.Api", apiJar.toRealPath()), resolved.selectedClasses());
        }
    }

    @Test void moduleDescriptorMetadataDoesNotCreateANamedModuleOnTheClasspath() throws Exception {
        Path providerJar = compile("provider", Map.of(
            "fixture.Api", "package fixture; public class Api {}",
            "module-info", "module fixture.provider { exports fixture; }"), "");
        Path moduleJar = compile("module", Map.of("module.Entry", "package module; public class Entry {}"), "");
        try (URLClassLoader provider = loader(providerJar);
             ModuleClassLoader module = prepare(providerJar, provider).newLoader(moduleJar, ClassLoader.getPlatformClassLoader())) {
            Class<?> api = module.loadClass("fixture.Api");
            assertSame(provider.loadClass("fixture.Api"), api);
            assertFalse(api.getModule().isNamed());
        }
    }

    private static ModuleAst.Import imported(String type, String provider) {
        return new ModuleAst.Import(type, provider, 1);
    }

    private ResolvedClasspath prepare(Path jar, ClassLoader loader) throws IOException {
        return DependencyClasspath.prepare("", List.of(new DependencyClasspath.Provider("Provider", jar, loader)),
            List.of(imported("fixture.Api", "Provider")), ClassLoader.getPlatformClassLoader());
    }

    private static URLClassLoader loader(Path jar) throws IOException {
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private Path compile(String id, Map<String, String> sources, String classpath) throws IOException {
        return ProviderFixtures.compile(temporary, id, sources, classpath);
    }
}
