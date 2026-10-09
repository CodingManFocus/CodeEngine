package kr.codenamemc.codeengine.runtime;

import io.papermc.paper.plugin.configuration.PluginMeta;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.compiler.ModuleCompiler;
import kr.codenamemc.codeengine.compiler.Parser;
import kr.codenamemc.codeengine.runtime.dependency.DependencyClasspath;
import kr.codenamemc.codeengine.runtime.dependency.ProviderFixtures;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginDependencyRegistryTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usePreservesProviderClassesAndInvalidatesOnDisableForBothMetadataFormats(boolean paper) throws Exception {
        ClassLoader parent = CodeModule.class.getClassLoader();
        String base = RuntimeClasspath.collect(CodeModule.class, Bukkit.class, net.kyori.adventure.text.Component.class);
        Path jar = ProviderFixtures.compile(temporary, "provider", Map.of(
            "fixture.ProviderPlugin", "package fixture; public class ProviderPlugin extends org.bukkit.plugin.java.JavaPlugin {}",
            "fixture.Api", """
                package fixture; public class Api {
                    public static int calls;
                    public static void call() { calls++; }
                }
                """), base);
        try (URLClassLoader providerLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, parent);
             var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            JavaPlugin engine = mock(JavaPlugin.class);
            Server server = mock(Server.class);
            PluginManager manager = mock(PluginManager.class);
            JavaPlugin provider = mock(providerLoader.loadClass("fixture.ProviderPlugin").asSubclass(JavaPlugin.class));
            PluginMeta meta = paper ? mock(PluginMeta.class) : new PluginDescriptionFile("Provider", "1.0", "fixture.ProviderPlugin");
            when(provider.getPluginMeta()).thenReturn(meta);
            when(provider.getName()).thenReturn("Provider");
            when(provider.isEnabled()).thenReturn(true);
            when(engine.getName()).thenReturn("CodeEngine");
            when(engine.getServer()).thenReturn(server);
            when(server.getPluginManager()).thenReturn(manager);
            when(manager.getPlugin("Provider")).thenReturn(provider);
            List<Plugin> stopped = new ArrayList<>();
            try (var registry = new PluginDependencyRegistry(engine, stopped::add)) {
                var ast = new Parser("""
                    module paperapi;
                    use fixture.Api from "Provider";
                    enable { Api.call(); }
                    """).parse();
                var snapshot = registry.snapshot(ast.pluginDependencies());
                var providers = snapshot.providers(ast.imports());
                assertEquals(1, providers.size());
                assertSame(providerLoader, providers.getFirst().loader());
                var resolved = DependencyClasspath.prepare(base, providers, ast.imports(), parent);
                var compiler = new ModuleCompiler(temporary.resolve("builds"), base);
                var compiled = compiler.compile(ast, ast.id(), resolved.selectedClasses());
                var prepared = resolved.prepareLoader(compiled.jar());
                try (var moduleLoader = resolved.newLoader(prepared, parent)) {
                    Class<?> api = providerLoader.loadClass("fixture.Api");
                    assertSame(api, moduleLoader.loadClass("fixture.Api"));
                    var module = moduleLoader.loadClass(compiled.className()).asSubclass(CodeModule.class).getConstructor().newInstance();
                    module.prepare(null);
                    module.enable();
                    assertEquals(1, api.getField("calls").getInt(null));
                }
                assertTrue(snapshot.available());
                // Paper may emit this event while isEnabled() still returns true.
                registry.onPluginDisable(new PluginDisableEvent(provider));
                assertEquals(List.of(provider), stopped);
                assertFalse(snapshot.available());
                assertThrows(IllegalStateException.class, snapshot::validate);
                assertThrows(IllegalStateException.class, () -> registry.snapshot(ast.pluginDependencies()));
            }
        }
    }
}
