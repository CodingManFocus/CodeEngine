package kr.codenamemc.codeengine.runtime;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import kr.codenamemc.codeengine.api.ModuleContext;
import org.bukkit.*;
import org.bukkit.command.CommandExecutor;
import org.bukkit.event.*;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Registrations are staged, attached and detached as one main-thread lifecycle unit. */
final class ModuleScope implements ModuleContext {
    private final JavaPlugin plugin;
    private final String id;
    private final Path data;
    // Paper unregisters by Listener identity; caller-supplied listeners may be shared.
    private final Listener eventOwner = new Listener() { };
    private final EventActivity eventActivity = new EventActivity();
    private final List<EventRegistration> events = new ArrayList<>();
    private final List<NativeCommand> commands = new ArrayList<>();
    private final List<TimerRegistration> timers = new ArrayList<>();
    private final List<BukkitRunnable> runningTasks = new ArrayList<>();
    private boolean sealed;
    ModuleScope(JavaPlugin plugin, String id, Path data) { this.plugin = plugin; this.id = id; this.data = data; }
    @Override public JavaPlugin plugin() { return plugin; }
    @Override public Server server() { return plugin.getServer(); }
    @Override public Path dataDirectory() { return data; }
    @Override public void listen(Class<? extends Event> type, Listener listener, EventPriority priority, boolean ignoreCancelled, EventExecutor executor) {
        mutable();
        events.add(new EventRegistration(Objects.requireNonNull(type), Objects.requireNonNull(listener),
            Objects.requireNonNull(priority), ignoreCancelled, Objects.requireNonNull(executor)));
    }
    @Override public void command(String name, String permission, CommandExecutor executor) {
        mutable();
        if (!name.matches("[a-z][a-z0-9_]{0,47}") || name.equals("ce") || name.equals("codeengine")) throw new IllegalArgumentException("Invalid command name");
        if (commands.stream().anyMatch(c -> c.getName().equals(name))) throw new IllegalArgumentException("Duplicate command: " + name);
        commands.add(new NativeCommand(plugin, name, permission, executor));
    }
    @Override public void every(long delayTicks, long periodTicks, Runnable action) {
        mutable();
        if (delayTicks < 1 || periodTicks < 1) throw new IllegalArgumentException("Tick counts must be positive");
        timers.add(new TimerRegistration(delayTicks, periodTicks, Objects.requireNonNull(action)));
    }
    void preflight() {
        requireMain();
        var map = server().getCommandMap().getKnownCommands();
        for (NativeCommand command : commands) {
            for (String key : List.of(command.getName(), namespace() + ":" + command.getName())) {
                var existing = map.get(key);
                if (existing != null)
                    throw new IllegalStateException("Command collision: " + key);
            }
        }
    }
    void activate() {
        requireMain(); sealed = true;
        try {
            for (EventRegistration event : events) plugin.getServer().getPluginManager().registerEvent(
                event.type(), eventOwner, event.priority(),
                new ScopedEventExecutor(eventActivity, event.listener(), event.executor()), plugin, event.ignoreCancelled());
            for (NativeCommand command : commands) {
                if (!server().getCommandMap().register(namespace(), command)) throw new IllegalStateException("Command collision: " + command.getName());
            }
            for (TimerRegistration timer : timers) {
                BukkitRunnable task = new BukkitRunnable() {
                    @Override public void run() {
                        try { timer.action().run(); }
                        catch (Throwable e) {
                            cancel(); plugin.getLogger().log(Level.SEVERE, "[" + id + "] Timer failed and was cancelled", e);
                        }
                    }
                };
                task.runTaskTimer(plugin, timer.delay(), timer.period()); runningTasks.add(task);
            }
        } catch (RuntimeException | LinkageError e) { deactivate(); throw e; }
    }
    void deactivate() {
        requireMain();
        sealed = true;
        eventActivity.close();
        for (BukkitRunnable task : runningTasks) task.cancel();
        runningTasks.clear();
        HandlerList.unregisterAll(eventOwner);
        var commandMap = server().getCommandMap();
        CommandBindings.removeOwned(commandMap.getKnownCommands(), commands);
        for (NativeCommand command : commands) command.unregister(commandMap);
    }
    java.util.concurrent.CompletableFuture<Void> drained() { return eventActivity.drained(); }
    private String namespace() { return "codeengine_" + id; }
    private void mutable() { requireMain(); if (sealed) throw new IllegalStateException("Registrations are only allowed during prepare/enable"); }
    static void requireMain() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Module lifecycle must run on the server thread"); }
    private record EventRegistration(Class<? extends Event> type, Listener listener, EventPriority priority, boolean ignoreCancelled, EventExecutor executor) { }
    private record TimerRegistration(long delay, long period, Runnable action) { }
}
