package kr.codenamemc.codeengine.api;

import java.nio.file.Path;
import org.bukkit.Server;
import org.bukkit.command.CommandExecutor;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

/** Lifecycle ownership only: all game values remain native Paper objects. */
public interface ModuleContext {
    JavaPlugin plugin();
    Server server();
    Path dataDirectory();
    void listen(Class<? extends Event> type, Listener listener, EventPriority priority,
                boolean ignoreCancelled, EventExecutor executor);
    void command(String name, String permission, CommandExecutor executor);
    void every(long delayTicks, long periodTicks, Runnable action);
    /**
     * Registers independent cleanup during prepare/enable. Runs once, in reverse order,
     * after disable and managed callbacks. One failure does not prevent later cleanup.
     * A stopped provider does not suppress local cleanup. External async work is not tracked.
     * Engine shutdown with callbacks still running cannot safely execute user hooks.
     */
    void onClose(AutoCloseable resource);
    /**
     * Registers provider-specific cleanup. Skips only this registration if the named,
     * declared provider has stopped or changed. The provider must own its own shutdown cleanup.
     */
    void onPluginClose(String pluginName, AutoCloseable resource);
    /** Checks a declared provider's original instance and lifetime; server thread only. */
    boolean isPluginAvailable(String pluginName);
}
