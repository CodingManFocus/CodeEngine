package kr.codenamemc.codeengine.runtime;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.compiler.*;
import kr.codenamemc.codeengine.workspace.ScriptStore;

/** Compilation is serialized off-thread; all lifecycle transitions execute on the server thread. */
public final class ModuleManager implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ScriptStore store;
    private final ModuleCompiler compiler;
    private final ModuleDisposer disposer;
    private final Map<String, LoadedModule> loaded = new HashMap<>();
    private final Set<CompiledModule> pendingBuilds = ConcurrentHashMap.newKeySet();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<String>> pending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16), runnable -> { Thread t = new Thread(runnable, "CodeEngine-Compiler"); t.setDaemon(true); return t; });
    private volatile Set<String> loadedIds = Set.of();
    private volatile boolean closed;
    public ModuleManager(JavaPlugin plugin, ScriptStore store, ModuleCompiler compiler) {
        this.plugin = plugin; this.store = store; this.compiler = compiler;
        disposer = new ModuleDisposer(plugin, plugin.getConfig().getLong("unloadTimeoutSeconds", 10));
    }
    public Set<String> loadedIds() { return loadedIds; }
    public Set<String> stoppingIds() { return disposer.stoppingIds(); }
    public CompletableFuture<String> submit(String id, String operation) {
        ScriptStore.validateId(id);
        if (!Set.of("build", "load", "reload", "unload").contains(operation)) throw new IllegalArgumentException("Unknown operation");
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Engine stopped"));
        if (stoppingIds().contains(id)) return CompletableFuture.failedFuture(new IllegalStateException("Module is still stopping: " + id));
        if (!busy.add(id)) return CompletableFuture.failedFuture(new IllegalStateException("Module is busy"));
        CompletableFuture<String> result = new CompletableFuture<>();
        pending.add(result);
        CompletableFuture<String> exposed = result.whenComplete((value, error) -> { busy.remove(id); pending.remove(result); });
        if (operation.equals("build")) compile(id, operation, result);
        else schedule(() -> begin(id, operation, result), null, result);
        return exposed;
    }
    private void begin(String id, String operation, CompletableFuture<String> result) {
        ModuleScope.requireMain();
        try {
            requireOpen();
            if (stoppingIds().contains(id)) throw new IllegalStateException("Module is still stopping: " + id);
            if (operation.equals("unload") || operation.equals("reload") && loaded.containsKey(id)) {
                unload(id).whenComplete((value, error) -> {
                    if (error != null) fail(id, error, result);
                    else if (!result.isDone()) {
                        if (operation.equals("unload")) result.complete("unload succeeded: " + id);
                        else try { requireOpen(); compile(id, operation, result); }
                        catch (Throwable failure) { fail(id, failure, result); }
                    }
                });
                return;
            }
            // Reload uses the same unload and load paths. No compilation or activation
            // starts until the old module has finished shutting down successfully.
            if (loaded.containsKey(id)) throw new IllegalStateException("Module already loaded; use reload");
            compile(id, operation, result);
        } catch (Throwable e) {
            fail(id, e, result);
        }
    }
    private CompletableFuture<Void> unload(String id) {
        LoadedModule current = loaded.remove(id);
        if (current == null) throw new IllegalStateException("Module is not loaded");
        try { return disposer.dispose(current, null); }
        finally { publishLoaded(); }
    }
    private void compile(String id, String operation, CompletableFuture<String> result) {
        try {
            worker.execute(() -> {
                CompiledModule compiled = null;
                try {
                    requireOpen();
                    compiled = compiler.compile(store.read(id).source(), id);
                    pendingBuilds.add(compiled);
                    requireOpen();
                    if (operation.equals("build")) {
                        cleanup(compiled); result.complete("Build succeeded: " + id); return;
                    }
                    CompiledModule ready = compiled;
                    schedule(() -> load(ready, operation, result), ready, result);
                } catch (Throwable e) { cleanup(compiled); fail(id, e, result); }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("Compiler queue is full or stopped"));
        }
    }
    private void load(CompiledModule compiled, String operation, CompletableFuture<String> result) {
        ModuleScope.requireMain();
        URLClassLoader loader = null;
        ModuleScope scope = null;
        CodeModule module = null;
        try {
            requireOpen();
            Path data = plugin.getDataFolder().toPath().resolve("data").resolve(compiled.id());
            Files.createDirectories(data);
            scope = new ModuleScope(plugin, compiled.id(), data);
            loader = new URLClassLoader(new java.net.URL[]{compiled.jar().toUri().toURL()}, CodeModule.class.getClassLoader());
            module = Class.forName(compiled.className(), true, loader).asSubclass(CodeModule.class).getConstructor().newInstance();
            module.prepare(scope);
            scope.preflight();
            module.enable();
            scope.preflight();
            scope.activate();
            loaded.put(compiled.id(), new LoadedModule(module, scope, loader, compiled));
            pendingBuilds.remove(compiled);
            publishLoaded();
            result.complete(operation + " succeeded: " + compiled.id());
        } catch (Throwable error) {
            if (loader == null) { cleanup(compiled); fail(compiled.id(), error, result); }
            else {
                // A partially activated module can already have an async callback running.
                // Transfer artifact ownership before waiting; shutdown must not delete it.
                pendingBuilds.remove(compiled);
                disposer.dispose(new LoadedModule(module, scope, loader, compiled), error)
                    .whenComplete((value, failure) -> fail(compiled.id(), failure == null ? error : failure, result));
            }
        }
    }
    private void schedule(Runnable action, CompiledModule compiled, CompletableFuture<String> result) {
        try { plugin.getServer().getScheduler().runTask(plugin, action); }
        catch (Throwable e) { cleanup(compiled); result.completeExceptionally(e); }
    }
    private void requireOpen() {
        if (closed) throw new IllegalStateException("Engine stopped");
    }
    private void fail(String id, Throwable error, CompletableFuture<String> result) {
        plugin.getLogger().log(Level.WARNING, "Module operation failed: " + id, error);
        result.completeExceptionally(error);
    }
    private void publishLoaded() {
        loadedIds = Set.copyOf(loaded.keySet());
        refreshCommands();
    }
    private void refreshCommands() {
        try { plugin.getServer().getOnlinePlayers().forEach(org.bukkit.entity.Player::updateCommands); }
        catch (RuntimeException e) { plugin.getLogger().log(Level.WARNING, "Command tree refresh failed", e); }
    }
    private void cleanup(CompiledModule compiled) {
        if (compiled == null) return;
        pendingBuilds.remove(compiled);
        try { ModuleCompiler.deleteBuild(compiled.jar().getParent()); }
        catch (java.io.IOException e) { plugin.getLogger().log(Level.WARNING, "Build cleanup failed", e); }
    }
    @Override public void close() {
        ModuleScope.requireMain();
        if (closed) return;
        closed = true; worker.shutdownNow();
        for (var future : pending) future.completeExceptionally(new IllegalStateException("Engine stopped"));
        for (CompiledModule compiled : pendingBuilds) cleanup(compiled);
        disposer.close(loaded.values());
        loaded.clear(); loadedIds = Set.of();
    }
}
