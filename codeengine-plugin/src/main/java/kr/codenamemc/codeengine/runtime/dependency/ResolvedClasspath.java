package kr.codenamemc.codeengine.runtime.dependency;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Compiler and runtime share the same immutable provider selection. */
public final class ResolvedClasspath {
    private final Set<Path> baseEntries;
    private final Set<String> baseClasses;
    private final Map<String, DependencyClasspath.Provider> owners;
    private final String additionalClasspath;

    ResolvedClasspath(Set<Path> baseEntries, Set<String> baseClasses, Set<Path> providerJars,
                      Map<String, DependencyClasspath.Provider> owners) {
        this.baseEntries = Set.copyOf(baseEntries);
        this.baseClasses = Set.copyOf(baseClasses);
        this.owners = Map.copyOf(owners);
        additionalClasspath = providerJars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    public String additionalClasspath() { return additionalClasspath; }

    public ModuleClassLoader newLoader(Path moduleJar, ClassLoader engineParent) throws IOException {
        Path jar = moduleJar.toRealPath();
        Set<String> moduleClasses = ClasspathIndex.read(jar);
        for (String name : moduleClasses) {
            if (baseClasses.contains(name) || owners.containsKey(name))
                throw new IOException("Generated module duplicates an engine/server/provider class: " + name);
        }
        return new ModuleClassLoader(jar, engineParent, baseEntries, owners, moduleClasses);
    }
}
