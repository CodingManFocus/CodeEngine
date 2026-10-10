package kr.codenamemc.codeengine.runtime.dependency;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.codenamemc.codeengine.compiler.ModuleAst;
import kr.codenamemc.codeengine.compiler.SourceException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** Builds an immutable, explicit dependency index away from the server thread. */
public final class DependencyClasspath {
    private DependencyClasspath() { }

    public record Provider(String name, Path jar, ClassLoader loader) {
        public Provider {
            Objects.requireNonNull(name);
            Objects.requireNonNull(jar);
            Objects.requireNonNull(loader);
        }
    }

    /** Captured on the server thread. A missing source still identifies a plugin's defining loader. */
    public record PluginOrigin(String name, Path jar, ClassLoader loader) {
        public PluginOrigin {
            Objects.requireNonNull(name);
            Objects.requireNonNull(loader);
        }
    }

    public static ResolvedClasspath prepare(String baseClasspath, List<Provider> providers,
                                            List<ModuleAst.Import> imports, ClassLoader engineParent) throws IOException {
        return prepare(baseClasspath, providers, providers.stream()
            .map(provider -> new PluginOrigin(provider.name(), provider.jar(), provider.loader())).toList(), imports, engineParent);
    }

    public static ResolvedClasspath prepare(String baseClasspath, List<Provider> providers, List<PluginOrigin> knownPlugins,
                                            List<ModuleAst.Import> imports, ClassLoader engineParent) throws IOException {
        Set<Path> baseEntries = new LinkedHashSet<>();
        Set<String> baseClasses = new HashSet<>();
        for (String entry : baseClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry).toRealPath();
            if (baseEntries.add(path)) baseClasses.addAll(ClasspathIndex.read(path));
        }
        Map<String, Provider> byName = new java.util.LinkedHashMap<>();
        Map<String, Set<String>> classesByProvider = new HashMap<>();
        Map<Path, String> selectedJars = new HashMap<>();
        Map<String, Provider> selectedProviders = new HashMap<>();
        for (Provider requested : providers) {
            if (byName.putIfAbsent(requested.name(), requested) != null)
                throw new IOException("Duplicate plugin dependency: " + requested.name());
        }
        for (ModuleAst.Import imported : imports) {
            if (imported.pluginName().isEmpty()) continue;
            Provider provider = byName.get(imported.pluginName());
            if (provider == null) throw new SourceException(imported.line(), "Undeclared provider: " + imported.pluginName());
            provider = selectProvider(provider, selectedJars, selectedProviders, classesByProvider);
            String binaryName = binaryName(imported.type(), classesByProvider.get(provider.name()));
            if (binaryName == null) throw new SourceException(imported.line(), "Plugin " + provider.name()
                + " does not contain imported type: " + imported.type());
        }
        List<Provider> candidates = providers.stream()
            .map(provider -> selectedProviders.getOrDefault(provider.name(), provider)).toList();
        List<PluginOrigin> plugins = new java.util.ArrayList<>();
        for (PluginOrigin plugin : knownPlugins) {
            Path jar = plugin.jar();
            if (jar != null) {
                try { jar = jar.toRealPath(); }
                catch (IOException ignored) { jar = jar.toAbsolutePath().normalize(); }
            }
            plugins.add(new PluginOrigin(plugin.name(), jar, plugin.loader()));
        }
        ApiTypeGraph graph = new ApiTypeGraph(baseEntries, baseClasses, candidates, plugins, engineParent);
        for (ModuleAst.Import imported : imports) {
            if (imported.pluginName().isEmpty()) continue;
            Provider provider = selectedProviders.get(imported.pluginName());
            String binaryName = binaryName(imported.type(), classesByProvider.get(provider.name()));
            try { graph.addRoot(binaryName, provider); }
            catch (IOException error) { throw new SourceException(imported.line(), error.getMessage()); }
        }
        Map<String, ResolvedApiType> owners = graph.resolve();
        Set<Path> validated = new HashSet<>();
        for (var selected : owners.values()) {
            if (!validated.add(selected.jar())) continue;
            Provider plugin = candidates.stream().filter(provider -> {
                try { return provider.jar().toRealPath().equals(selected.jar()); }
                catch (IOException ignored) { return false; }
            }).findFirst().orElse(null);
            if (plugin != null) selectProvider(plugin, selectedJars, selectedProviders, classesByProvider);
            else validateLibraryJar(selected.jar());
        }
        for (ModuleAst.Import imported : imports) {
            if (!imported.pluginName().isEmpty() || binaryName(imported.type(), baseClasses) != null) continue;
            for (Provider provider : providers) {
                Set<String> classes = classesByProvider.get(provider.name());
                if (classes == null) {
                    // Only a diagnostic: an unsupported, lifecycle-only JAR is not an API dependency.
                    try { classes = ClasspathIndex.read(provider.jar()); }
                    catch (IOException ignored) { continue; }
                    classesByProvider.put(provider.name(), classes);
                }
                if (binaryName(imported.type(), classes) != null)
                    throw new SourceException(imported.line(), "External import must specify its provider: use "
                        + imported.type() + " from \"PluginName\";");
            }
        }
        return new ResolvedClasspath(baseEntries, baseClasses, owners);
    }

    private static Provider selectProvider(Provider requested, Map<Path, String> selectedJars,
            Map<String, Provider> selectedProviders, Map<String, Set<String>> classesByProvider) throws IOException {
        Provider selected = selectedProviders.get(requested.name());
        if (selected != null) return selected;
        Path jar = requested.jar().toRealPath();
        if (!Files.isRegularFile(jar)) throw new IOException("Plugin API must be in a JAR: " + requested.name());
        String other = selectedJars.putIfAbsent(jar, requested.name());
        if (other != null && !other.equals(requested.name()))
            throw new IOException("Plugin dependencies share a JAR: " + requested.name());
        validateProviderJar(requested.name(), jar);
        selected = new Provider(requested.name(), jar, requested.loader());
        classesByProvider.put(requested.name(), ClasspathIndex.read(jar));
        selectedProviders.put(requested.name(), selected);
        return selected;
    }

    private static String binaryName(String sourceName, Set<String> classes) {
        String candidate = sourceName;
        while (true) {
            if (classes.contains(candidate)) return candidate;
            int separator = candidate.lastIndexOf('.');
            if (separator < 0) return null;
            candidate = candidate.substring(0, separator) + '$' + candidate.substring(separator + 1);
        }
    }

    private static void validateLibraryJar(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var manifest = jar.getManifest();
            String extraClasspath = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (extraClasspath != null && !extraClasspath.isBlank())
                throw new IOException("API library JARs with manifest Class-Path are not supported: " + path.getFileName());
        }
    }

    private static void validateProviderJar(String name, Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            if (jar.isMultiRelease() || jar.stream().anyMatch(entry ->
                    entry.getName().startsWith("META-INF/versions/") && entry.getName().endsWith(".class")))
                throw new IOException("Multi-release plugin API JARs are not supported: " + name);
            var manifest = jar.getManifest();
            String extraClasspath = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (extraClasspath != null && !extraClasspath.isBlank())
                throw new IOException("Plugin API JARs with manifest Class-Path are not supported: " + name);
        }
    }
}
