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

    public static ResolvedClasspath prepare(String baseClasspath, List<Provider> providers,
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
        Set<Path> providerJars = new LinkedHashSet<>();
        for (Provider requested : providers) {
            if (byName.containsKey(requested.name())) throw new IOException("Duplicate plugin dependency: " + requested.name());
            Path jar = requested.jar().toRealPath();
            if (!Files.isRegularFile(jar)) throw new IOException("Plugin API must be in a JAR: " + requested.name());
            if (!providerJars.add(jar)) throw new IOException("Plugin dependencies share a JAR: " + requested.name());
            validateProviderJar(requested.name(), jar);
            byName.put(requested.name(), new Provider(requested.name(), jar, requested.loader()));
            classesByProvider.put(requested.name(), ClasspathIndex.read(jar));
        }
        ApiTypeGraph graph = new ApiTypeGraph(baseEntries, baseClasses, List.copyOf(byName.values()), engineParent);
        for (ModuleAst.Import imported : imports) {
            if (imported.pluginName().isEmpty()) continue;
            Provider provider = byName.get(imported.pluginName());
            if (provider == null) throw new SourceException(imported.line(), "Undeclared provider: " + imported.pluginName());
            String binaryName = binaryName(imported.type(), classesByProvider.get(provider.name()));
            if (binaryName == null) throw new SourceException(imported.line(), "Plugin " + provider.name()
                + " does not contain imported type: " + imported.type());
            try { graph.addRoot(binaryName, provider); }
            catch (IOException error) { throw new SourceException(imported.line(), error.getMessage()); }
        }
        Map<String, Provider> owners = graph.resolve();
        for (ModuleAst.Import imported : imports) {
            if (!imported.pluginName().isEmpty() || binaryName(imported.type(), baseClasses) != null) continue;
            for (Set<String> classes : classesByProvider.values()) {
                if (binaryName(imported.type(), classes) != null)
                    throw new SourceException(imported.line(), "External import must specify its provider: use "
                        + imported.type() + " from \"PluginName\";");
            }
        }
        return new ResolvedClasspath(baseEntries, baseClasses, owners);
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
