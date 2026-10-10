package kr.codenamemc.codeengine.runtime.dependency;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

/** Resolves the types exposed by selected APIs without initializing classes or examining method bodies. */
final class ApiTypeGraph {
    private final Set<Path> baseEntries;
    private final Set<String> baseClasses;
    private final List<DependencyClasspath.Provider> providers;
    private final List<DependencyClasspath.PluginOrigin> plugins;
    private final ClassLoader engineParent;
    private final Map<String, Class<?>> identities = new HashMap<>();
    private final Map<String, ResolvedApiType> owners = new LinkedHashMap<>();
    private final Deque<Pending> pending = new ArrayDeque<>();
    private final Set<Type> genericTypes = new HashSet<>();
    private final Set<Path> checkedLibraries = new HashSet<>();

    ApiTypeGraph(Set<Path> baseEntries, Set<String> baseClasses,
                 List<DependencyClasspath.Provider> providers, List<DependencyClasspath.PluginOrigin> plugins, ClassLoader engineParent) {
        this.baseEntries = baseEntries;
        this.baseClasses = baseClasses;
        this.providers = providers;
        this.plugins = plugins;
        this.engineParent = engineParent;
    }

    void addRoot(String binaryName, DependencyClasspath.Provider provider) throws IOException {
        try {
            Class<?> type = Class.forName(binaryName, false, provider.loader());
            if (type.getClassLoader() != provider.loader() || !provider.jar().equals(ClassOrigin.path(type)))
                throw new IOException("Plugin " + provider.name() + " resolved " + binaryName
                    + " from a different loader or JAR than its compile-time API");
            include(type, provider);
        } catch (ClassNotFoundException | LinkageError error) {
            throw new IOException("Cannot resolve API " + binaryName + " from " + provider.name() + ": " + error, error);
        }
    }

    Map<String, ResolvedApiType> resolve() throws IOException {
        try {
            while (!pending.isEmpty()) {
                Pending next = pending.removeFirst();
                inspect(next.type(), next.provider());
            }
        } catch (LinkageError | TypeNotPresentException | MalformedParameterizedTypeException | SecurityException error) {
            throw new IOException("Cannot resolve selected API signatures; check declared providers and their API libraries: " + error, error);
        }
        return Map.copyOf(owners);
    }

    private void include(Class<?> type, DependencyClasspath.Provider context) throws IOException {
        while (type.isArray()) type = type.getComponentType();
        if (type.isPrimitive()) return;
        Class<?> previous = identities.putIfAbsent(type.getName(), type);
        if (previous != null) {
            if (previous != type) throw new IOException("Conflicting API type " + type.getName()
                + ": selected APIs expose different class identities; one module can use only one provider per binary name");
            return;
        }
        if (type.getClassLoader() == null || type.getClassLoader() == ClassLoader.getPlatformClassLoader()) return;
        Path origin = ClassOrigin.path(type);
        if (origin != null && baseEntries.contains(origin)) {
            try {
                if (engineParent.loadClass(type.getName()) != type)
                    throw new IOException("API exposes a different server/engine type: " + type.getName());
            } catch (ClassNotFoundException error) { throw new IOException("Base API type is unavailable: " + type.getName(), error); }
            return;
        }
        if (baseClasses.contains(type.getName()))
            throw new IOException("Selected API duplicates a server/engine class: " + type.getName());
        DependencyClasspath.Provider owner = context;
        boolean pluginJar = false;
        for (DependencyClasspath.PluginOrigin plugin : plugins) {
            boolean sameJar = origin != null && plugin.jar() != null && origin.equals(plugin.jar());
            if (!sameJar && plugin.loader() != type.getClassLoader()) continue;
            DependencyClasspath.Provider declared = providers.stream()
                .filter(provider -> provider.name().equals(plugin.name())).findFirst().orElse(null);
            if (declared == null)
                throw new IOException("API signature exposes " + type.getName() + " from undeclared plugin "
                    + plugin.name() + "; add requires plugin \"" + plugin.name() + "\";");
            if (declared.loader() != type.getClassLoader())
                throw new IOException("Plugin " + declared.name() + " resolved " + type.getName() + " from a different loader");
            owner = declared;
            pluginJar |= sameJar;
        }
        if (origin == null || !java.nio.file.Files.isRegularFile(origin))
            throw new IOException("API signature type has no local source JAR: " + type.getName());
        if (!pluginJar && checkedLibraries.add(origin)) {
            try (var jar = new java.util.jar.JarFile(origin.toFile())) {
                if (jar.getEntry("plugin.yml") != null || jar.getEntry("paper-plugin.yml") != null)
                    throw new IOException("API signature exposes " + type.getName()
                        + " from an undeclared plugin JAR; declare its provider explicitly");
            }
        }
        try {
            if (Class.forName(type.getName(), false, owner.loader()) != type)
                throw new IOException("API library type is not visible with the same class identity from "
                    + owner.name() + ": " + type.getName());
        } catch (ClassNotFoundException error) {
            throw new IOException("API library type is unavailable from " + owner.name() + ": " + type.getName(), error);
        }
        owners.put(type.getName(), new ResolvedApiType(type, origin));
        pending.addLast(new Pending(type, owner));
    }

    private record Pending(Class<?> type, DependencyClasspath.Provider provider) { }

    private void inspect(Class<?> type, DependencyClasspath.Provider provider) throws IOException {
        if (type.getDeclaringClass() != null) include(type.getDeclaringClass(), provider);
        includeType(type.getSuperclass(), provider);
        includeTypes(type.getInterfaces(), provider);
        includeType(type.getGenericSuperclass(), provider);
        includeTypes(type.getGenericInterfaces(), provider);
        includeTypes(type.getTypeParameters(), provider);
        for (Field field : type.getDeclaredFields()) {
            if (exported(field.getModifiers())) {
                include(field.getType(), provider);
                includeType(field.getGenericType(), provider);
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!exported(method.getModifiers())) continue;
            include(method.getReturnType(), provider);
            includeType(method.getGenericReturnType(), provider);
            includeExecutable(method, provider);
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (exported(constructor.getModifiers())) includeExecutable(constructor, provider);
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (exported(nested.getModifiers())) include(nested, provider);
        }
    }

    private void includeExecutable(Executable executable, DependencyClasspath.Provider provider) throws IOException {
        includeTypes(executable.getParameterTypes(), provider);
        includeTypes(executable.getExceptionTypes(), provider);
        includeTypes(executable.getGenericParameterTypes(), provider);
        includeTypes(executable.getGenericExceptionTypes(), provider);
        includeTypes(executable.getTypeParameters(), provider);
    }

    private void includeTypes(Type[] types, DependencyClasspath.Provider provider) throws IOException {
        for (Type type : types) includeType(type, provider);
    }

    private void includeType(Type type, DependencyClasspath.Provider provider) throws IOException {
        if (type == null || !genericTypes.add(type)) return;
        if (type instanceof Class<?> concrete) include(concrete, provider);
        else if (type instanceof ParameterizedType parameterized) {
            includeType(parameterized.getRawType(), provider);
            includeType(parameterized.getOwnerType(), provider);
            includeTypes(parameterized.getActualTypeArguments(), provider);
        } else if (type instanceof GenericArrayType array) includeType(array.getGenericComponentType(), provider);
        else if (type instanceof TypeVariable<?> variable) includeTypes(variable.getBounds(), provider);
        else if (type instanceof WildcardType wildcard) {
            includeTypes(wildcard.getUpperBounds(), provider);
            includeTypes(wildcard.getLowerBounds(), provider);
        }
    }

    private static boolean exported(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
