package kr.codenamemc.codeengine.runtime.dependency;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Compiler and runtime share the same immutable provider selection. */
public final class ResolvedClasspath {
    private final Set<Path> baseEntries;
    private final Set<String> baseClasses;
    private final Map<String, ResolvedApiType> owners;
    private final Map<String, Path> selectedClasses;

    ResolvedClasspath(Set<Path> baseEntries, Set<String> baseClasses,
                      Map<String, ResolvedApiType> owners) {
        this.baseEntries = Set.copyOf(baseEntries);
        this.baseClasses = Set.copyOf(baseClasses);
        this.owners = Map.copyOf(owners);
        selectedClasses = owners.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().jar()));
    }

    public Map<String, Path> selectedClasses() { return selectedClasses; }

    /** Reads and validates the generated JAR before entering the server-thread lifecycle. */
    public PreparedModule prepareLoader(Path moduleJar) throws IOException {
        Path jar = moduleJar.toRealPath();
        Set<String> moduleClasses = ClasspathIndex.read(jar);
        for (String name : moduleClasses) {
            if (baseClasses.contains(name) || owners.containsKey(name))
                throw new IOException("Generated module duplicates an engine/server/provider class: " + name);
        }
        return new PreparedModule(jar, Set.copyOf(moduleClasses));
    }

    public ModuleClassLoader newLoader(PreparedModule prepared, ClassLoader engineParent) throws IOException {
        return new ModuleClassLoader(prepared.jar(), engineParent, baseEntries, owners, prepared.classes());
    }

    public ModuleClassLoader newLoader(Path moduleJar, ClassLoader engineParent) throws IOException {
        return newLoader(prepareLoader(moduleJar), engineParent);
    }

    public record PreparedModule(Path jar, Set<String> classes) { }
}
