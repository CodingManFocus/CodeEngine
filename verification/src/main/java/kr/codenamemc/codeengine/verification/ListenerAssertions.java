package kr.codenamemc.codeengine.verification;

import java.util.List;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;

/** Excludes only the engine's dependency lifecycle listener; all module registrations still count. */
final class ListenerAssertions {
    private static final String dependencyRegistry = "kr.codenamemc.codeengine.runtime.PluginDependencyRegistry";

    private ListenerAssertions() { }

    static List<RegisteredListener> managedListeners(JavaPlugin engine) {
        ClassLoader engineLoader = engine.getClass().getClassLoader();
        return HandlerList.getRegisteredListeners(engine).stream().filter(registration -> {
            Class<?> listenerType = registration.getListener().getClass();
            return !listenerType.getName().equals(dependencyRegistry) || listenerType.getClassLoader() != engineLoader;
        }).toList();
    }

    static RegisteredListener singleManagedListener(JavaPlugin engine) {
        List<RegisteredListener> listeners = managedListeners(engine);
        if (listeners.size() != 1) {
            throw new AssertionError("Expected exactly one managed listener, found " + listeners.size());
        }
        return listeners.getFirst();
    }
}
