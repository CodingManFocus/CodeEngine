package kr.codenamemc.codeengine.runtime;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import kr.codenamemc.codeengine.compiler.ModuleAst;
import kr.codenamemc.codeengine.compiler.SourceException;
import kr.codenamemc.codeengine.runtime.dependency.DependencyClasspath;

/** Main-thread snapshots of explicitly declared providers. Never mutates Paper's plugin graph. */
final class PluginDependencyRegistry implements Listener, AutoCloseable {
    private final JavaPlugin engine;
    private final Set<Plugin> stopped = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Consumer<Plugin> stopDependentModules;

    PluginDependencyRegistry(JavaPlugin engine, Consumer<Plugin> stopDependentModules) {
        this.engine = engine;
        this.stopDependentModules = stopDependentModules;
        engine.getServer().getPluginManager().registerEvents(this, engine);
    }

    Snapshot snapshot(List<String> names) {
        ModuleScope.requireMain();
        List<Binding> bindings = new ArrayList<>();
        for (String name : names) {
            if (name.equalsIgnoreCase(engine.getName()))
                throw new IllegalArgumentException("CodeEngine is already available; it cannot be a plugin dependency");
            Plugin provider = engine.getServer().getPluginManager().getPlugin(name);
            if (provider == null || !provider.isEnabled())
                throw new IllegalStateException("Required plugin is missing or disabled: " + name);
            if (!provider.getName().equals(name))
                throw new IllegalArgumentException("Use the exact plugin name: " + provider.getName());
            if (stopped.contains(provider))
                throw new IllegalStateException("Required plugin was stopped; restart the server before using it again: " + name);
            bindings.add(new Binding(provider, provider.getClass().getClassLoader()));
        }
        return new Snapshot(bindings);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin provider = event.getPlugin();
        if (provider == engine) return;
        // isEnabled may still be true during this event. The invalidation is permanent
        // for this provider instance, including builds already running on the worker.
        stopped.add(provider);
        stopDependentModules.accept(provider);
    }

    @Override public void close() { HandlerList.unregisterAll(this); }

    private record Binding(Plugin plugin, ClassLoader loader) { }

    final class Snapshot {
        private final List<Binding> bindings;
        private Snapshot(List<Binding> bindings) { this.bindings = List.copyOf(bindings); }
        List<DependencyClasspath.Provider> providers(List<ModuleAst.Import> imports) {
            Map<String, Integer> importedProviders = new HashMap<>();
            for (ModuleAst.Import imported : imports) {
                if (!imported.pluginName().isEmpty())
                    importedProviders.putIfAbsent(imported.pluginName(), imported.line());
            }
            List<DependencyClasspath.Provider> providers = new ArrayList<>();
            for (Binding binding : bindings) {
                Plugin plugin = binding.plugin();
                Integer line = importedProviders.get(plugin.getName());
                // Both plugin.yml and paper-plugin.yml providers use JavaPlugin. Keep
                // their actual defining loader; metadata format does not determine API access.
                if (!(plugin instanceof JavaPlugin)) {
                    if (line != null) throw new SourceException(line,
                        "Only JavaPlugin API providers are supported: " + plugin.getName());
                    continue;
                }
                var source = plugin.getClass().getProtectionDomain().getCodeSource();
                if (source == null || source.getLocation() == null || !source.getLocation().getProtocol().equals("file")) {
                    if (line != null) throw new SourceException(line, "Provider has no local plugin JAR: " + plugin.getName());
                    continue;
                }
                try {
                    Path jar = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                    providers.add(new DependencyClasspath.Provider(plugin.getName(), jar, binding.loader()));
                } catch (java.net.URISyntaxException | IllegalArgumentException error) {
                    if (line != null) throw new SourceException(line, "Invalid provider JAR location: " + plugin.getName());
                }
            }
            return List.copyOf(providers);
        }
        boolean contains(Plugin plugin) { return bindings.stream().anyMatch(binding -> binding.plugin() == plugin); }
        boolean available(String name) {
            ModuleScope.requireMain();
            Binding binding = bindings.stream().filter(item -> item.plugin().getName().equals(name))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Undeclared plugin: " + name));
            return available(binding);
        }
        boolean available() {
            ModuleScope.requireMain();
            return bindings.stream().allMatch(this::available);
        }
        private boolean available(Binding binding) {
            Plugin provider = binding.plugin();
            return !stopped.contains(provider) && provider.isEnabled()
                && engine.getServer().getPluginManager().getPlugin(provider.getName()) == provider
                && provider.getClass().getClassLoader() == binding.loader();
        }
        void validate() {
            if (!available()) throw new IllegalStateException("A required plugin stopped or changed during this module operation; restart the server");
        }
    }
}
