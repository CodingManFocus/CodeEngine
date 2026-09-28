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

    public static ResolvedClasspath prepare(String baseClasspath, List<Provider> providers) throws IOException {
        Set<Path> baseEntries = new LinkedHashSet<>();
        Set<String> baseClasses = new HashSet<>();
        for (String entry : baseClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry).toRealPath();
            if (baseEntries.add(path)) baseClasses.addAll(ClasspathIndex.read(path));
        }
        Map<String, Provider> owners = new HashMap<>();
        Set<Path> providerJars = new LinkedHashSet<>();
        Set<String> names = new HashSet<>();
        for (Provider requested : providers) {
            if (!names.add(requested.name())) throw new IOException("Duplicate plugin dependency: " + requested.name());
            Path jar = requested.jar().toRealPath();
            if (!Files.isRegularFile(jar)) throw new IOException("Plugin API must be in a JAR: " + requested.name());
            if (!providerJars.add(jar)) throw new IOException("Plugin dependencies share a JAR: " + requested.name());
            validateProviderJar(requested.name(), jar);
            Provider provider = new Provider(requested.name(), jar, requested.loader());
            for (String binaryName : ClasspathIndex.read(jar)) {
                if (baseClasses.contains(binaryName)) throw new IOException("Plugin " + provider.name()
                    + " duplicates a server/engine class: " + binaryName);
                Provider existing = owners.putIfAbsent(binaryName, provider);
                if (existing != null) throw new IOException("Plugin dependencies " + existing.name() + " and "
                    + provider.name() + " contain the same class: " + binaryName);
            }
        }
        return new ResolvedClasspath(baseEntries, baseClasses, providerJars, owners);
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
