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
    private final ClassLoader engineParent;
    private final Map<String, Class<?>> identities = new HashMap<>();
    private final Map<String, DependencyClasspath.Provider> owners = new LinkedHashMap<>();
    private final Deque<Class<?>> pending = new ArrayDeque<>();
    private final Set<Type> genericTypes = new HashSet<>();

    ApiTypeGraph(Set<Path> baseEntries, Set<String> baseClasses,
                 List<DependencyClasspath.Provider> providers, ClassLoader engineParent) {
        this.baseEntries = baseEntries;
        this.baseClasses = baseClasses;
        this.providers = providers;
        this.engineParent = engineParent;
    }

    void addRoot(String binaryName, DependencyClasspath.Provider provider) throws IOException {
        try {
            Class<?> type = Class.forName(binaryName, false, provider.loader());
            if (type.getClassLoader() != provider.loader() || !provider.jar().equals(ClassOrigin.path(type)))
                throw new IOException("Plugin " + provider.name() + " resolved " + binaryName
                    + " from a different loader or JAR than its compile-time API");
            include(type);
        } catch (ClassNotFoundException | LinkageError error) {
            throw new IOException("Cannot resolve API " + binaryName + " from " + provider.name(), error);
        }
    }

    Map<String, DependencyClasspath.Provider> resolve() throws IOException {
        try {
            while (!pending.isEmpty()) inspect(pending.removeFirst());
        } catch (LinkageError | TypeNotPresentException | MalformedParameterizedTypeException | SecurityException error) {
            throw new IOException("Cannot resolve selected API signatures; check declared providers and their API libraries", error);
        }
        return Map.copyOf(owners);
    }

    private void include(Class<?> type) throws IOException {
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
        for (DependencyClasspath.Provider provider : providers) {
            if (provider.loader() == type.getClassLoader() && provider.jar().equals(origin)) {
                owners.put(type.getName(), provider);
                pending.addLast(type);
                return;
            }
        }
        throw new IOException("API signature exposes " + type.getName()
            + " from an undeclared plugin or unsupported library loader; declare its provider explicitly");
    }

    private void inspect(Class<?> type) throws IOException {
        if (type.getDeclaringClass() != null) include(type.getDeclaringClass());
        includeType(type.getSuperclass());
        includeTypes(type.getInterfaces());
        includeType(type.getGenericSuperclass());
        includeTypes(type.getGenericInterfaces());
        includeTypes(type.getTypeParameters());
        for (Field field : type.getDeclaredFields()) {
            if (exported(field.getModifiers())) {
                include(field.getType());
                includeType(field.getGenericType());
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!exported(method.getModifiers())) continue;
            include(method.getReturnType());
            includeType(method.getGenericReturnType());
            includeExecutable(method);
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (exported(constructor.getModifiers())) includeExecutable(constructor);
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (exported(nested.getModifiers())) include(nested);
        }
    }

    private void includeExecutable(Executable executable) throws IOException {
        includeTypes(executable.getParameterTypes());
        includeTypes(executable.getExceptionTypes());
        includeTypes(executable.getGenericParameterTypes());
        includeTypes(executable.getGenericExceptionTypes());
        includeTypes(executable.getTypeParameters());
    }

    private void includeTypes(Type[] types) throws IOException {
        for (Type type : types) includeType(type);
    }

    private void includeType(Type type) throws IOException {
        if (type == null || !genericTypes.add(type)) return;
        if (type instanceof Class<?> concrete) include(concrete);
        else if (type instanceof ParameterizedType parameterized) {
            includeType(parameterized.getRawType());
            includeType(parameterized.getOwnerType());
            includeTypes(parameterized.getActualTypeArguments());
        } else if (type instanceof GenericArrayType array) includeType(array.getGenericComponentType());
        else if (type instanceof TypeVariable<?> variable) includeTypes(variable.getBounds());
        else if (type instanceof WildcardType wildcard) {
            includeTypes(wildcard.getUpperBounds());
            includeTypes(wildcard.getLowerBounds());
        }
    }

    private static boolean exported(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
