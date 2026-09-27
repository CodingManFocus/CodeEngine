package kr.codenamemc.codeengine.runtime;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import kr.codenamemc.codeengine.api.ModuleContext;
import org.bukkit.*;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
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
    private final ResourceScope resources = new ResourceScope();
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
        commands.add(new NativeCommand(plugin, name, permission,
            new ScopedCommandExecutor(eventActivity, Objects.requireNonNull(executor))));
    }
    @Override public void every(long delayTicks, long periodTicks, Runnable action) {
        mutable();
        if (delayTicks < 1 || periodTicks < 1) throw new IllegalArgumentException("Tick counts must be positive");
        timers.add(new TimerRegistration(delayTicks, periodTicks,
            new ScopedTask(eventActivity, Objects.requireNonNull(action))));
    }
    @Override public void onClose(AutoCloseable resource) {
        mutable();
        resources.add(resource);
    }
    Throwable closeResources(BooleanSupplier cleanupAllowed) {
        requireMain();
        sealed = true;
        return resources.close(cleanupAllowed);
    }
    void discardResources() {
        requireMain();
        sealed = true;
        resources.discard();
    }
    void beginActivation() {
        requireMain();
        if (!eventActivity.enter(false)) throw new IllegalStateException("Module is already stopping");
    }
    void endActivation() {
        requireMain();
        eventActivity.leave(false);
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
        } catch (RuntimeException | LinkageError e) {
            try { deactivate(); }
            catch (Throwable cleanupError) { if (e != cleanupError) e.addSuppressed(cleanupError); }
            throw e;
        }
    }
    void deactivate() {
        requireMain();
        sealed = true;
        Throwable failure = null;
        try { eventActivity.close(); }
        catch (Throwable error) { failure = combine(failure, error); }
        for (BukkitRunnable task : runningTasks) {
            try { task.cancel(); }
            catch (Throwable error) { failure = combine(failure, error); }
        }
        runningTasks.clear();
        try { HandlerList.unregisterAll(eventOwner); }
        catch (Throwable error) { failure = combine(failure, error); }
        CommandMap commandMap = null;
        try { commandMap = server().getCommandMap(); }
        catch (Throwable error) { failure = combine(failure, error); }
        if (commandMap != null) {
            for (NativeCommand command : commands) {
                try { CommandBindings.removeOwned(commandMap.getKnownCommands(), List.of(command)); }
                catch (Throwable error) { failure = combine(failure, error); }
                try { command.unregister(commandMap); }
                catch (Throwable error) { failure = combine(failure, error); }
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Module registration cleanup failed", failure);
    }
    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
    java.util.concurrent.CompletableFuture<Void> drained() { return eventActivity.drained(); }
    private String namespace() { return "codeengine_" + id; }
    private void mutable() { requireMain(); if (sealed) throw new IllegalStateException("Registrations are only allowed during prepare/enable"); }
    static void requireMain() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Module lifecycle must run on the server thread"); }
    private record EventRegistration(Class<? extends Event> type, Listener listener, EventPriority priority, boolean ignoreCancelled, EventExecutor executor) { }
    private record TimerRegistration(long delay, long period, Runnable action) { }
}
