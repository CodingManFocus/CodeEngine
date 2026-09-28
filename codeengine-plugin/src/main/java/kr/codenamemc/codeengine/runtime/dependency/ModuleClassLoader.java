package kr.codenamemc.codeengine.runtime.dependency;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Defines module classes only; provider classes always retain their actual defining loader. */
public final class ModuleClassLoader extends URLClassLoader {
    static { registerAsParallelCapable(); }
    private final Set<Path> baseEntries;
    private final Map<String, DependencyClasspath.Provider> owners;
    private final Path moduleJar;
    private final Set<String> moduleClasses;

    ModuleClassLoader(Path moduleJar, ClassLoader parent, Set<Path> baseEntries,
                      Map<String, DependencyClasspath.Provider> owners, Set<String> moduleClasses) throws IOException {
        super(new URL[]{moduleJar.toUri().toURL()}, parent);
        this.baseEntries = baseEntries;
        this.owners = owners;
        this.moduleJar = moduleJar;
        this.moduleClasses = Set.copyOf(moduleClasses);
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                DependencyClasspath.Provider provider = owners.get(name);
                if (moduleClasses.contains(name)) type = loadModuleClass(name);
                else if (provider != null) type = loadProviderClass(name, provider);
                else {
                    type = getParent().loadClass(name);
                    if (!allowedParent(type))
                        throw new ClassNotFoundException("Undeclared plugin class is not accessible: " + name);
                }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }

    private Class<?> loadModuleClass(String name) throws ClassNotFoundException {
        // Exact own-JAR classes never pass through the engine's plugin loader.
        // That loader may already be closed when an admitted callback is unwinding.
        Class<?> type = findClass(name);
        if (type.getClassLoader() != this || !moduleJar.equals(ClassOrigin.path(type)))
            throw new ClassNotFoundException("Generated module class resolved outside its own JAR: " + name);
        return type;
    }

    private static Class<?> loadProviderClass(String name, DependencyClasspath.Provider provider) throws ClassNotFoundException {
        Class<?> type = provider.loader().loadClass(name);
        if (type.getClassLoader() != provider.loader() || !provider.jar().equals(ClassOrigin.path(type)))
            throw new ClassNotFoundException("Plugin " + provider.name() + " resolved " + name
                + " from a different loader or JAR than its compile-time API");
        return type;
    }

    private boolean allowedParent(Class<?> type) {
        if (type.getClassLoader() == null) return true;
        var domain = type.getProtectionDomain();
        var source = domain == null ? null : domain.getCodeSource();
        if (source != null && source.getLocation() != null && source.getLocation().getProtocol().equals("jrt")) return true;
        Path path = ClassOrigin.path(type);
        return path != null && baseEntries.contains(path);
    }

}
