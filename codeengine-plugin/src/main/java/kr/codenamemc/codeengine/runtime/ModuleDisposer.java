package kr.codenamemc.codeengine.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import kr.codenamemc.codeengine.compiler.ModuleCompiler;

/** Owns detached modules until every managed callback has returned. */
final class ModuleDisposer {
    private final JavaPlugin plugin;
    private final long timeoutNanos;
    private final Map<String, Disposal> stopping = new LinkedHashMap<>();
    private volatile Set<String> stoppingIds = Set.of();
    private BukkitTask pollTask;
    private boolean shutdownCompleted;
    ModuleDisposer(JavaPlugin plugin, long timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 300)
            throw new IllegalArgumentException("unloadTimeoutSeconds must be between 1 and 300");
        this.plugin = plugin; timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
    }
    Set<String> stoppingIds() { return stoppingIds; }
    CompletableFuture<Void> dispose(LoadedModule module, Throwable initialFailure) {
        ModuleScope.requireMain();
        Disposal disposal = detach(module, initialFailure);
        if (module.scope().drained().isDone()) finish(disposal);
        else if (shutdownCompleted) deferArtifactCleanup(disposal);
        else if (pollTask == null) {
            try { pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 1, 1); }
            catch (Throwable error) {
                // Preserve ownership and artifacts if the scheduler is already stopping.
                disposal.result.completeExceptionally(error);
                plugin.getLogger().log(Level.SEVERE, "Could not schedule module cleanup: " + module.compiled().id(), error);
            }
        }
        return disposal.result;
    }
    private Disposal detach(LoadedModule module, Throwable initialFailure) {
        String id = module.compiled().id();
        Disposal disposal = new Disposal(module, initialFailure, System.nanoTime() + timeoutNanos);
        if (stopping.putIfAbsent(id, disposal) != null) throw new IllegalStateException("Module is already stopping: " + id);
        stoppingIds = Set.copyOf(stopping.keySet());
        try { module.scope().deactivate(); }
        catch (Throwable error) { disposal.failure = combine(disposal.failure, error); }
        return disposal;
    }
    private void poll() {
        ModuleScope.requireMain();
        for (Disposal disposal : List.copyOf(stopping.values())) {
            if (stopping.get(disposal.module.compiled().id()) != disposal) continue;
            if (disposal.module.scope().drained().isDone()) finish(disposal);
            else if (!disposal.result.isDone() && System.nanoTime() - disposal.deadline >= 0) {
                var error = new TimeoutException("Module is still stopping: " + disposal.module.compiled().id()
                    + "; managed callbacks have not returned. New loads are blocked until cleanup completes.");
                if (disposal.failure != null) error.addSuppressed(disposal.failure);
                disposal.result.completeExceptionally(error);
            }
        }
        stopPollingIfIdle();
    }
    private void finish(Disposal disposal) {
        if (disposal.finishing) return;
        disposal.finishing = true;
        Throwable failure = disposal.failure;
        boolean cleanupAllowed = false;
        try { cleanupAllowed = cleanupAllowed(disposal.module); }
        catch (Throwable error) { failure = combine(failure, error); }
        if (cleanupAllowed) {
            if (disposal.module.module() != null) {
                try { disposal.module.module().disable(); }
                catch (Throwable error) { failure = combine(failure, error); }
            }
            try {
                if (cleanupAllowed(disposal.module)) {
                    failure = combine(failure, disposal.module.scope().closeResources(() -> cleanupAllowed(disposal.module)));
                } else failure = skipUserCleanup(disposal.module, failure);
            } catch (Throwable error) {
                failure = combine(failure, error);
                failure = skipUserCleanup(disposal.module, failure);
            }
        } else {
            failure = skipUserCleanup(disposal.module, failure);
        }
        failure = closeArtifacts(disposal.module, failure);
        disposal.failure = failure;
        stopping.remove(disposal.module.compiled().id(), disposal);
        stoppingIds = Set.copyOf(stopping.keySet());
        stopPollingIfIdle();
        if (failure == null) {
            if (!disposal.result.complete(null)) plugin.getLogger().info("Deferred module cleanup completed: " + disposal.module.compiled().id());
        } else if (!disposal.result.completeExceptionally(failure)) {
            plugin.getLogger().log(Level.WARNING, "Deferred module cleanup failed: " + disposal.module.compiled().id(), failure);
        }
    }
    private boolean cleanupAllowed(LoadedModule module) {
        return !shutdownCompleted && module.cleanupAllowed().getAsBoolean();
    }
    private Throwable skipUserCleanup(LoadedModule module, Throwable failure) {
        String message = (shutdownCompleted ? "Engine shutdown completed" : "Dependency stopped")
            + "; user disable/cleanup skipped: " + module.compiled().id();
        plugin.getLogger().warning(message);
        module.scope().discardResources();
        return combine(failure, new IllegalStateException(message));
    }
    private void stopPollingIfIdle() {
        if (stopping.isEmpty() && pollTask != null) { pollTask.cancel(); pollTask = null; }
    }
    void close(Collection<LoadedModule> loaded) {
        ModuleScope.requireMain();
        try {
            if (pollTask != null) {
                try { pollTask.cancel(); }
                catch (Throwable error) { plugin.getLogger().log(Level.WARNING, "Could not cancel module cleanup polling", error); }
                finally { pollTask = null; }
            }
            for (LoadedModule module : loaded) detach(module, null);
            for (Disposal disposal : List.copyOf(stopping.values())) {
                // A user disable hook can synchronously initiate engine shutdown.
                // Its outer finish call must remain the sole owner of final cleanup.
                if (disposal.finishing) continue;
                if (disposal.module.scope().drained().isDone()) {
                    finish(disposal);
                    if (disposal.failure != null) plugin.getLogger().log(Level.WARNING,
                        "Module shutdown failed: " + disposal.module.compiled().id(), disposal.failure);
                    continue;
                }
                deferArtifactCleanup(disposal);
            }
        } finally {
            shutdownCompleted = true;
            stopping.clear(); stoppingIds = Set.of();
        }
    }
    private void deferArtifactCleanup(Disposal disposal) {
        if (disposal.finishing) return;
        disposal.finishing = true;
        String id = disposal.module.compiled().id();
        plugin.getLogger().warning("Server/plugin shutdown while module callbacks are running: " + id
            + "; disable hook and registered resource cleanup skipped. Artifacts stay open until callbacks return.");
        disposal.module.scope().discardResources();
        disposal.failure = combine(disposal.failure, new IllegalStateException("Engine stopped while module callbacks are running: " + id));
        disposal.result.completeExceptionally(disposal.failure);
        stopping.remove(id, disposal);
        stoppingIds = Set.copyOf(stopping.keySet());
        // The future retains ownership after removal from the main-thread map.
        // No Paper API or user hook may run on its callback thread after shutdown.
        disposal.module.scope().drained().thenRun(() -> {
            Throwable failure = closeArtifacts(disposal.module, null);
            if (failure != null) plugin.getLogger().log(Level.WARNING, "Shutdown artifact cleanup failed: " + id, failure);
        });
    }
    private static Throwable closeArtifacts(LoadedModule module, Throwable failure) {
        try { module.loader().close(); }
        catch (Throwable error) { failure = combine(failure, error); }
        try { ModuleCompiler.deleteBuild(module.compiled().jar().getParent()); }
        catch (Throwable error) { failure = combine(failure, error); }
        return failure;
    }
    private static Throwable combine(Throwable first, Throwable next) {
        if (next == null) return first;
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
    private static final class Disposal {
        private final LoadedModule module;
        private final long deadline;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private Throwable failure;
        private boolean finishing;
        Disposal(LoadedModule module, Throwable failure, long deadline) {
            this.module = module; this.failure = failure; this.deadline = deadline;
        }
    }
}
