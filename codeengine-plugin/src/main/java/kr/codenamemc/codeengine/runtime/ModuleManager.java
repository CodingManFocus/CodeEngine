package kr.codenamemc.codeengine.runtime;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.compiler.*;
import kr.codenamemc.codeengine.workspace.ModuleSourceStore;
import kr.codenamemc.codeengine.runtime.dependency.DependencyClasspath;
import kr.codenamemc.codeengine.runtime.dependency.ResolvedClasspath;

/** Compilation is serialized off-thread; all lifecycle transitions execute on the server thread. */
public final class ModuleManager implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ModuleSourceStore store;
    private final ModuleCompiler compiler;
    private final ModuleDisposer disposer;
    private final PluginDependencyRegistry dependencies;
    private final Map<String, LoadedModule> loaded = new HashMap<>();
    private final Map<String, LoadedModule> activating = new HashMap<>();
    private final Map<String, PluginDependencyRegistry.Snapshot> moduleDependencies = new HashMap<>();
    private final Set<CompiledModule> pendingBuilds = ConcurrentHashMap.newKeySet();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<String>> pending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16), runnable -> { Thread t = new Thread(runnable, "CodeEngine-Compiler"); t.setDaemon(true); return t; });
    private volatile Set<String> loadedIds = Set.of();
    private volatile boolean closed;
    private boolean commandRefreshScheduled;
    public ModuleManager(JavaPlugin plugin, ModuleSourceStore store, ModuleCompiler compiler) {
        this.plugin = plugin; this.store = store; this.compiler = compiler;
        disposer = new ModuleDisposer(plugin, plugin.getConfig().getLong("unloadTimeoutSeconds", 10));
        dependencies = new PluginDependencyRegistry(plugin, this::dependencyStopped);
    }
    public Set<String> loadedIds() { return loadedIds; }
    public Set<String> stoppingIds() { return disposer.stoppingIds(); }
    public CompletableFuture<String> submit(String id, String operation) {
        ModuleSourceStore.validateId(id);
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
        moduleDependencies.remove(id);
        if (current == null) throw new IllegalStateException("Module is not loaded");
        try { return disposer.dispose(current, null); }
        finally { publishLoaded(); }
    }
    private void compile(String id, String operation, CompletableFuture<String> result) {
        executeWorker(id, result, () -> {
            ModuleAst ast = new Parser(store.read(id).source()).parse();
            if (!ast.id().equals(id)) throw new SourceException(1, "Module id must match file name: " + id);
            schedule(() -> prepareCompilation(ast, operation, result), null, result);
        });
    }
    private void prepareCompilation(ModuleAst ast, String operation, CompletableFuture<String> result) {
        ModuleScope.requireMain();
        try {
            requireOpen();
            PluginDependencyRegistry.Snapshot snapshot = dependencies.snapshot(ast.pluginDependencies());
            List<DependencyClasspath.Provider> providers = snapshot.providers(ast.imports());
            executeWorker(ast.id(), result, () -> {
                CompiledModule compiled = null;
                try {
                    requireOpen();
                    ResolvedClasspath classpath = DependencyClasspath.prepare(compiler.baseClasspath(), providers, snapshot.plugins(), ast.imports(), CodeModule.class.getClassLoader());
                    compiled = compiler.compile(ast, ast.id(), classpath.selectedClasses());
                    pendingBuilds.add(compiled);
                    requireOpen();
                    CompiledModule ready = compiled;
                    ResolvedClasspath.PreparedModule prepared = operation.equals("build") ? null : classpath.prepareLoader(ready.jar());
                    schedule(() -> completeCompilation(ready, classpath, prepared, snapshot, operation, result), ready, result);
                } catch (Throwable e) { cleanup(compiled); fail(ast.id(), e, result); }
            });
        } catch (Throwable e) { fail(ast.id(), e, result); }
    }
    private void executeWorker(String id, CompletableFuture<String> result, WorkerAction action) {
        try {
            worker.execute(() -> {
                try { requireOpen(); action.run(); }
                catch (Throwable error) { fail(id, error, result); }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("Compiler queue is full or stopped"));
        }
    }
    @FunctionalInterface private interface WorkerAction { void run() throws Exception; }
    private void completeCompilation(CompiledModule compiled, ResolvedClasspath classpath, ResolvedClasspath.PreparedModule prepared,
            PluginDependencyRegistry.Snapshot snapshot, String operation, CompletableFuture<String> result) {
        ModuleScope.requireMain();
        try {
            requireOpen();
            snapshot.validate();
            if (operation.equals("build")) {
                cleanup(compiled); result.complete("Build succeeded: " + compiled.id());
            } else load(compiled, classpath, prepared, snapshot, operation, result);
        } catch (Throwable error) { cleanup(compiled); fail(compiled.id(), error, result); }
    }
    private void load(CompiledModule compiled, ResolvedClasspath classpath, ResolvedClasspath.PreparedModule prepared,
            PluginDependencyRegistry.Snapshot snapshot, String operation, CompletableFuture<String> result) {
        ModuleScope.requireMain();
        URLClassLoader loader = null;
        ModuleScope scope = null;
        LoadedModule candidate = null;
        boolean activationEntered = false;
        try {
            requireOpen();
            Path data = plugin.getDataFolder().toPath().resolve("data").resolve(compiled.id());
            Files.createDirectories(data);
            scope = new ModuleScope(plugin, compiled.id(), data, snapshot::available);
            snapshot.validate();
            loader = classpath.newLoader(prepared, CodeModule.class.getClassLoader());
            // Publish ownership before any module class initializer, constructor or hook can
            // reenter shutdown. Its activation guard keeps artifacts open until this call exits.
            candidate = new LoadedModule(null, scope, loader, compiled);
            activating.put(compiled.id(), candidate);
            pendingBuilds.remove(compiled);
            scope.beginActivation();
            activationEntered = true;
            CodeModule module = Class.forName(compiled.className(), true, loader).asSubclass(CodeModule.class).getConstructor().newInstance();
            candidate.initialize(module);
            requireOpen();
            snapshot.validate();
            module.prepare(scope);
            requireOpen();
            scope.preflight();
            snapshot.validate();
            module.enable();
            requireOpen();
            scope.preflight();
            snapshot.validate();
            scope.activate();
            requireOpen();
            snapshot.validate();
            activating.remove(compiled.id(), candidate);
            loaded.put(compiled.id(), candidate);
            moduleDependencies.put(compiled.id(), snapshot);
            publishLoaded();
            result.complete(operation + " succeeded: " + compiled.id());
        } catch (Throwable error) {
            if (candidate == null) {
                if (loader != null) {
                    try { loader.close(); }
                    catch (Throwable cleanupError) { if (error != cleanupError) error.addSuppressed(cleanupError); }
                }
                cleanup(compiled); fail(compiled.id(), error, result);
            } else if (activating.remove(compiled.id(), candidate)) {
                // Transfer exactly once. A nested close already took ownership if removal fails.
                disposer.dispose(candidate, error)
                    .whenComplete((value, failure) -> fail(compiled.id(), failure == null ? error : failure, result));
            } else fail(compiled.id(), error, result);
        } finally {
            if (activationEntered) scope.endActivation();
        }
    }
    private void dependencyStopped(Plugin provider) {
        ModuleScope.requireMain();
        // Remove every dependent module's entry points before running any user hooks.
        List<LoadedModule> affected = new ArrayList<>();
        for (var entry : List.copyOf(moduleDependencies.entrySet())) {
            if (!entry.getValue().contains(provider)) continue;
            String id = entry.getKey();
            moduleDependencies.remove(id);
            LoadedModule module = loaded.remove(id);
            if (module != null) {
                affected.add(module);
                try { module.scope().deactivate(); }
                catch (Throwable error) { plugin.getLogger().log(Level.WARNING,
                    "Could not completely detach module " + id + "; cleanup will continue", error); }
            }
        }
        if (affected.isEmpty()) return;
        publishLoaded();
        for (LoadedModule module : affected) {
            plugin.getLogger().warning("Stopping module " + module.compiled().id() + ": dependency "
                + provider.getName() + " stopped. Provider hot reload is unsupported; restart the server.");
            disposer.dispose(module, new IllegalStateException("Required plugin stopped: " + provider.getName()))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) plugin.getLogger().log(Level.WARNING,
                        "Dependency shutdown: " + module.compiled().id(), failure);
                });
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
        if (closed || commandRefreshScheduled) return;
        commandRefreshScheduled = true;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                commandRefreshScheduled = false;
                if (closed) return;
                try { plugin.getServer().getOnlinePlayers().forEach(org.bukkit.entity.Player::updateCommands); }
                catch (RuntimeException error) { plugin.getLogger().log(Level.WARNING, "Command tree refresh failed", error); }
            });
        } catch (RuntimeException error) {
            commandRefreshScheduled = false;
            plugin.getLogger().log(Level.WARNING, "Could not schedule command tree refresh", error);
        }
    }
    private void cleanup(CompiledModule compiled) {
        if (compiled == null || !pendingBuilds.remove(compiled)) return;
        BuildCleanup.delete(compiled.jar().getParent()).whenComplete((ignored, error) -> {
            if (error != null) plugin.getLogger().log(Level.WARNING, "Build cleanup failed: " + compiled.id(), error);
        });
    }
    @Override public void close() {
        ModuleScope.requireMain();
        if (closed) return;
        closed = true; worker.shutdownNow();
        for (var future : pending) future.completeExceptionally(new IllegalStateException("Engine stopped"));
        for (CompiledModule compiled : pendingBuilds) cleanup(compiled);
        List<LoadedModule> shuttingDown = new ArrayList<>(loaded.values());
        shuttingDown.addAll(activating.values());
        // Transfer ownership before user hooks can synchronously stop a provider.
        loaded.clear(); activating.clear(); moduleDependencies.clear(); loadedIds = Set.of();
        disposer.close(shuttingDown);
        dependencies.close();
    }
}
