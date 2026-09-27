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
     * Registers cleanup for an external resource during prepare/enable, on the server thread.
     * Cleanup runs once in reverse registration order after disable and managed callbacks
     * have returned. It does not track external callbacks or wait for external asynchronous work.
     * If a dependency has stopped, or the engine stops before callbacks return, user cleanup is
     * skipped rather than invoking plugin APIs after their lifecycle has ended.
     */
    void onClose(AutoCloseable resource);
}
