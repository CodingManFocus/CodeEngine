package kr.codenamemc.codeengine.verification;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.compiler.ModuleCompiler;
import kr.codenamemc.codeengine.runtime.*;
import kr.codenamemc.codeengine.workspace.ModuleSourceStore;

/** Deterministic barriers around real Paper async event dispatch; no connected players. */
final class AsyncLifecycleVerification {
    private final JavaPlugin observer;
    private final JavaPlugin engine;
    private final ModuleManager manager;
    private final Path root;
    private final Consumer<String> passed;
    private final List<Probe> probes = new ArrayList<>();
    AsyncLifecycleVerification(JavaPlugin observer, JavaPlugin engine, ModuleManager manager, Consumer<String> passed) {
        this(observer, engine, manager, engine.getDataFolder().toPath(), passed);
    }
    private AsyncLifecycleVerification(JavaPlugin observer, JavaPlugin engine, ModuleManager manager, Path root, Consumer<String> passed) {
        this.observer = observer; this.engine = engine; this.manager = manager; this.root = root; this.passed = passed;
    }
    CompletableFuture<Void> run() {
        return regular("asyncunload", false, false)
            .thenCompose(value -> regular("asyncreload", true, false))
            .thenCompose(value -> regular("asyncthrow", false, true))
            .thenCompose(value -> timeout())
            .thenCompose(value -> partialActivation())
            .thenCompose(value -> shutdown(false))
            .thenCompose(value -> shutdown(true))
            .thenCompose(value -> shutdownFromDisable())
            .thenRun(() -> {
                check(manager.loadedIds().isEmpty() && manager.stoppingIds().isEmpty(), "async lifecycle leaves no loaded or stopping modules");
                check(HandlerList.getRegisteredListeners(engine).isEmpty(), "async lifecycle leaves no registered listeners");
                check(engine.getServer().getScheduler().getPendingTasks().stream().noneMatch(task -> task.getOwner() == engine), "async lifecycle leaves no cleanup poll task");
            }).whenComplete((value, error) -> {
                for (Probe probe : probes) probe.release.countDown();
                if (error == null) for (Probe probe : probes) probe.clear();
            });
    }
    private CompletableFuture<Void> regular(String id, boolean reload, boolean throwAfterReturn) {
        Probe probe = probe(id);
        System.setProperty(probe.key + "syncCall", "true");
        if (throwAfterReturn) System.setProperty(probe.key + "throw", "true");
        return source(id, probe.source(), "load")
            .thenRun(probe::start)
            .thenCompose(value -> probe.awaitEntry())
            .thenCompose(value -> {
                probe.cached = HandlerList.getRegisteredListeners(engine).getFirst();
                if (reload) write(id, probe.replacement());
                probe.operation = manager.submit(id, reload ? "reload" : "unload");
                return ticks(4);
            }).thenCompose(value -> {
                probe.wasPending = !probe.operation.isDone() && System.getProperty(probe.key + "disabled") == null;
                probe.keptArtifacts = hasBuild(id);
                // Only the fixed implementation has pending disposal here. The old JAR
                // is allowed to finish the original callback so its class-loading error is recorded.
                if (!probe.wasPending) return CompletableFuture.completedFuture(null);
                check(!manager.loadedIds().contains(id) && manager.stoppingIds().contains(id), "unload publishes stopping state while server ticks continue");
                check(engine.getServer().getCommandMap().getCommand("ce" + id) == null, "command removed before draining callbacks");
                return settle(CompletableFuture.runAsync(() -> {
                    try { probe.cached.callEvent(event()); } catch (Exception e) { throw new CompletionException(e); }
                }).orTimeout(5, TimeUnit.SECONDS)).thenRun(() -> check(probe.calls.get() == 1, "cached event dispatcher cannot enter after close fence"));
            }).thenRun(() -> probe.release.countDown())
            .thenCompose(value -> settle(probe.done))
            .thenCompose(value -> settle(probe.operation))
            .thenRun(() -> {
                check(probe.wasPending, "operation waited for running callback; observed callback error=" + probe.error.get());
                check(probe.keptArtifacts, "module artifacts retained while callback runs");
                check("yes".equals(System.getProperty(probe.key + "anonymous")), "previously unused anonymous class loads before cleanup");
                check("yes".equals(System.getProperty(probe.key + "syncResult")), "async callback can finish a scheduled main-thread operation during unload");
                check("true".equals(System.getProperty(probe.key + "disabled")), "disable runs on main thread after callback returns");
                check(throwAfterReturn ? probe.error.get() instanceof AssertionError : probe.error.get() == null, "callback exception still releases activity count");
                if (reload) check("yes".equals(System.getProperty(probe.key + "replacement")), "reload starts replacement only after old disable");
                else check(!hasBuild(id), "unload deletes artifacts after last callback");
            }).thenCompose(value -> reload ? operation(id, "unload").thenApply(result -> null) : CompletableFuture.completedFuture(null));
    }
    private CompletableFuture<Void> timeout() {
        Probe probe = probe("asynctimeout");
        return source(probe.id, probe.source(), "load")
            .thenRun(probe::start).thenCompose(value -> probe.awaitEntry())
            .thenCompose(value -> {
                write(probe.id, probe.replacement());
                probe.operation = manager.submit(probe.id, "reload");
                return settle(probe.operation.handle((result, error) -> {
                    check(rootCause(error) instanceof TimeoutException, "reload returns timeout while callback remains active"); return null;
                }));
            }).thenCompose(value -> {
                check(manager.stoppingIds().contains(probe.id) && hasBuild(probe.id), "timeout keeps module quarantined with open artifacts");
                check(System.getProperty(probe.key + "disabled") == null && System.getProperty(probe.key + "replacement") == null, "timeout neither disables active code nor starts replacement");
                CompletableFuture<Void> rejected = CompletableFuture.completedFuture(null);
                for (String action : List.of("load", "reload", "unload")) rejected = rejected.thenCompose(ignored ->
                    operation(probe.id, action).handle((result, error) -> {
                        check(error != null && rootCause(error).getMessage().contains("still stopping"), "timed-out module blocks " + action); return null;
                    }));
                return rejected;
            }).thenRun(() -> probe.release.countDown())
            .thenCompose(value -> settle(probe.done))
            .thenCompose(value -> until(() -> !manager.stoppingIds().contains(probe.id)))
            .thenRun(() -> {
                check(probe.error.get() == null && "yes".equals(System.getProperty(probe.key + "anonymous")), "timed-out callback can still load its own class");
                check("true".equals(System.getProperty(probe.key + "disabled")) && !hasBuild(probe.id), "late callback completion automatically finishes cleanup");
                check(probe.operation.isCompletedExceptionally() && System.getProperty(probe.key + "replacement") == null, "timed-out reload never restarts itself after late completion");
            }).thenCompose(value -> operation(probe.id, "load"))
            .thenRun(() -> check(manager.loadedIds().contains(probe.id), "explicit load succeeds after quarantine clears"))
            .thenCompose(value -> operation(probe.id, "unload")).thenApply(value -> null);
    }
    private CompletableFuture<Void> partialActivation() {
        Probe probe = probe("asyncpartial");
        System.getProperties().put(probe.key + "badType", FailingRegistrationEvent.class);
        FailingRegistrationEvent.trigger = () -> {
            probe.start();
            try { if (!probe.entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("partial activation callback did not start"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        };
        String source = probe.source() + "enable { ctx.listen((Class<? extends Event>)System.getProperties().get(\"" + probe.key
            + "badType\"), this, EventPriority.NORMAL, false, (listener, event) -> {}); }";
        write(probe.id, source); probe.operation = manager.submit(probe.id, "load");
        return probe.awaitEntry().thenCompose(value -> ticks(3))
            .thenRun(() -> {
                check(!probe.operation.isDone() && manager.stoppingIds().contains(probe.id), "failed partial activation waits for its admitted callback");
                check(hasBuild(probe.id) && System.getProperty(probe.key + "disabled") == null, "failed candidate keeps artifacts and user state until callback exits");
                probe.release.countDown();
            }).thenCompose(value -> settle(probe.done))
            .thenCompose(value -> settle(probe.operation.handle((result, error) -> {
                check(error != null, "partial registration failure returned after drain"); return null;
            })))
            .thenRun(() -> {
                check(probe.error.get() == null && "true".equals(System.getProperty(probe.key + "disabled")), "partial activation cleanup follows callback completion on main thread");
                check(!hasBuild(probe.id) && !manager.stoppingIds().contains(probe.id), "failed candidate artifacts cleaned without leak");
                FailingRegistrationEvent.trigger = null;
            });
    }
    private CompletableFuture<Void> shutdown(boolean alreadyStopping) {
        try {
            Path isolated = observer.getDataFolder().toPath().resolve(alreadyStopping ? "shutdown-stopping" : "shutdown-loaded");
            var isolatedManager = new ModuleManager(engine, new ModuleSourceStore(isolated.resolve("modules")),
                new ModuleCompiler(isolated.resolve("builds"), RuntimeClasspath.collect(CodeModule.class, Bukkit.class, Component.class)));
            var test = new AsyncLifecycleVerification(observer, engine, isolatedManager, isolated, passed);
            Probe probe = test.probe(alreadyStopping ? "asyncclosing" : "asyncshutdown");
            return test.source(probe.id, probe.source(), "load").thenRun(probe::start).thenCompose(value -> probe.awaitEntry())
                .thenCompose(value -> {
                    if (alreadyStopping) probe.operation = isolatedManager.submit(probe.id, "reload");
                    return ticks(3);
                }).thenRun(() -> {
                    isolatedManager.close();
                    check(test.hasBuild(probe.id) && System.getProperty(probe.key + "disabled") == null, "shutdown skips unsafe disable and keeps active artifacts");
                    if (alreadyStopping) check(probe.operation.isCompletedExceptionally(), "shutdown fails pending reload without starting replacement");
                    check(isolatedManager.submit(probe.id, "load").isCompletedExceptionally(), "closed manager rejects new load");
                }).thenCompose(value -> ticks(2)).thenRun(() -> probe.release.countDown())
                .thenCompose(value -> settle(probe.done))
                .thenRun(() -> {
                    check(probe.error.get() == null && "yes".equals(System.getProperty(probe.key + "anonymous")), "callback can finish safely after manager shutdown");
                    check(!test.hasBuild(probe.id), "late shutdown cleanup removes artifacts without main-thread scheduling");
                    check(System.getProperty(probe.key + "disabled") == null, "shutdown never moves disable hook to async thread");
                }).whenComplete((value, error) -> { probe.release.countDown(); if (error == null) probe.clear(); });
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }
    private CompletableFuture<Void> shutdownFromDisable() {
        String key = "codeengine.verification.reentrant.";
        try {
            Path isolated = observer.getDataFolder().toPath().resolve("shutdown-reentrant");
            var isolatedManager = new ModuleManager(engine, new ModuleSourceStore(isolated.resolve("modules")),
                new ModuleCompiler(isolated.resolve("builds"), RuntimeClasspath.collect(CodeModule.class, Bukkit.class, Component.class)));
            var calls = new AtomicInteger();
            System.getProperties().put(key + "calls", calls);
            System.getProperties().put(key + "close", (Runnable) isolatedManager::close);
            String source = """
                module reentrantclose;
                disable {
                    ((java.util.concurrent.atomic.AtomicInteger)System.getProperties().get("%1$scalls")).incrementAndGet();
                    ((Runnable)System.getProperties().get("%1$sclose")).run();
                    new Runnable() { public void run() { System.setProperty("%1$scompleted", "yes"); } }.run();
                }
                """.formatted(key);
            Files.writeString(isolated.resolve("modules/reentrantclose.ce"), source);
            return settle(isolatedManager.submit("reentrantclose", "load"))
                .thenCompose(value -> settle(isolatedManager.submit("reentrantclose", "unload").handle((result, error) -> {
                    check(error != null, "shutdown from disable fails outstanding operation"); return null;
                })))
                .thenRun(() -> {
                    check(calls.get() == 1 && "yes".equals(System.getProperty(key + "completed")), "reentrant shutdown does not repeat disable or close its active loader");
                    try (var files = Files.list(isolated.resolve("builds"))) {
                        check(files.findAny().isEmpty(), "reentrant shutdown finishes artifact cleanup exactly once");
                    } catch (Exception e) { throw new RuntimeException(e); }
                    isolatedManager.close();
                }).whenComplete((value, error) -> {
                    System.getProperties().remove(key + "calls"); System.getProperties().remove(key + "close"); System.clearProperty(key + "completed");
                });
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }
    private Probe probe(String id) { Probe probe = new Probe(id); probes.add(probe); return probe; }
    private final class Probe {
        private final String id, key;
        private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private CompletableFuture<Void> done;
        private CompletableFuture<String> operation;
        private RegisteredListener cached;
        private boolean wasPending, keptArtifacts;
        Probe(String id) {
            this.id = id; key = "codeengine.verification.async." + id + ".";
            System.getProperties().put(key + "entered", entered); System.getProperties().put(key + "release", release);
            System.getProperties().put(key + "calls", calls); System.getProperties().put(key + "error", error);
        }
        private String source() {
            return """
                module %1$s;
                command ce%1$s { return true; }
                on AsyncPlayerChatEvent event {
                    ((java.util.concurrent.atomic.AtomicInteger)System.getProperties().get("%2$scalls")).incrementAndGet();
                    ((java.util.concurrent.CountDownLatch)System.getProperties().get("%2$sentered")).countDown();
                    try {
                        if (!((java.util.concurrent.CountDownLatch)System.getProperties().get("%2$srelease")).await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("probe timed out");
                        new Runnable() { public void run() { System.setProperty("%2$sanonymous", "yes"); } }.run();
                        if (System.getProperty("%2$sdisabled") != null) throw new IllegalStateException("disable ran before callback completed");
                        if (Boolean.getBoolean("%2$ssyncCall")) ctx.server().getScheduler().callSyncMethod(ctx.plugin(), () -> System.setProperty("%2$ssyncResult", "yes")).get(5, java.util.concurrent.TimeUnit.SECONDS);
                        if (Boolean.getBoolean("%2$sthrow")) throw new AssertionError("expected callback failure");
                    } catch (Throwable error) {
                        ((java.util.concurrent.atomic.AtomicReference<Throwable>)System.getProperties().get("%2$serror")).set(error);
                        throw new RuntimeException(error);
                    }
                }
                disable { System.setProperty("%2$sdisabled", Boolean.toString(Bukkit.isPrimaryThread())); }
                """.formatted(id, key);
        }
        private String replacement() {
            return "module " + id + "; enable { if (!\"true\".equals(System.getProperty(\"" + key
                + "disabled\"))) throw new IllegalStateException(\"previous disable incomplete\"); System.setProperty(\"" + key + "replacement\", \"yes\"); }";
        }
        private void start() { done = CompletableFuture.runAsync(() -> engine.getServer().getPluginManager().callEvent(event())); }
        private CompletableFuture<Void> awaitEntry() {
            return settle(CompletableFuture.runAsync(() -> {
                try { if (!entered.await(8, TimeUnit.SECONDS)) throw new AssertionError("async event did not enter: " + id); }
                catch (InterruptedException e) { throw new CompletionException(e); }
            }));
        }
        private void clear() {
            for (String suffix : List.of("entered", "release", "calls", "error", "anonymous", "disabled", "replacement", "syncCall", "syncResult", "throw", "badType"))
                System.getProperties().remove(key + suffix);
        }
    }
    public static final class FailingRegistrationEvent extends Event {
        private static Runnable trigger;
        public static HandlerList getHandlerList() {
            trigger.run();
            throw new IllegalStateException("expected failure after first event registration");
        }
        @Override public HandlerList getHandlers() { throw new UnsupportedOperationException(); }
    }
    private AsyncPlayerChatEvent event() {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> { throw new AssertionError("Unexpected player access: " + method.getName()); });
        return new AsyncPlayerChatEvent(true, player, "probe", new HashSet<>());
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed.accept(message); }
    private Throwable rootCause(Throwable error) { if (error == null) return null; while (error.getCause() != null) error = error.getCause(); return error; }
    private void write(String id, String text) {
        try { Files.writeString(root.resolve("modules").resolve(id + ".ce"), text); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private boolean hasBuild(String id) {
        try (var files = Files.list(root.resolve("builds"))) { return files.anyMatch(path -> path.getFileName().toString().startsWith(id + "-")); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private CompletableFuture<String> source(String id, String text, String operation) { write(id, text); return operation(id, operation); }
    private CompletableFuture<String> operation(String id, String operation) { return settle(manager.submit(id, operation)); }
    private <T> CompletableFuture<T> settle(CompletableFuture<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        operation.whenComplete((value, error) -> observer.getServer().getScheduler().runTask(observer, () -> {
            if (error == null) result.complete(value); else result.completeExceptionally(error);
        })); return result;
    }
    private CompletableFuture<Void> ticks(long count) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        observer.getServer().getScheduler().runTaskLater(observer, () -> result.complete(null), count); return result;
    }
    private CompletableFuture<Void> until(BooleanSupplier condition) { return until(condition, 100); }
    private CompletableFuture<Void> until(BooleanSupplier condition, int remaining) {
        if (condition.getAsBoolean()) return CompletableFuture.completedFuture(null);
        if (remaining == 0) return CompletableFuture.failedFuture(new AssertionError("cleanup did not finish"));
        return ticks(1).thenCompose(value -> until(condition, remaining - 1));
    }
}
