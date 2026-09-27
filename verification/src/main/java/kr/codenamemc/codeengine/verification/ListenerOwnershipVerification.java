package kr.codenamemc.codeengine.verification;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import kr.codenamemc.codeengine.runtime.ModuleManager;

/** Shared caller Listener identity must not become shared registration ownership. */
final class ListenerOwnershipVerification {
    private static final String prefix = "codeengine.verification.shared.";
    private final JavaPlugin observer;
    private final JavaPlugin engine;
    private final ModuleManager manager;
    private final Consumer<String> passed;
    private final Listener shared = new Listener() { };
    private int externalCalls;
    ListenerOwnershipVerification(JavaPlugin observer, JavaPlugin engine, ModuleManager manager, Consumer<String> passed) {
        this.observer = observer; this.engine = engine; this.manager = manager; this.passed = passed;
    }
    CompletableFuture<Void> run() {
        System.getProperties().put(prefix + "listener", shared);
        for (String key : List.of("a", "b", "c", "order")) System.clearProperty(prefix + key);
        engine.getServer().getPluginManager().registerEvent(BlockBreakEvent.class, shared, EventPriority.LOWEST,
            (listener, event) -> {
                check(listener == shared && event == System.getProperties().get(prefix + "event"), "external registration retains original listener/event");
                externalCalls++; append("x");
            }, observer, false);
        String a = module("shareda", "a", "HIGH", true);
        String b = module("sharedb", "b", "NORMAL", false) + "command cesharedb { return true; }\n";
        CompletableFuture<Void> chain = source("shareda", a, "load")
            .thenCompose(value -> source("sharedb", b, "load"))
            .thenRun(() -> {
                check(count() == 2, "two managed registrations share caller Listener");
                fire(false);
                check(value("a") == 1 && value("b") == 1 && externalCalls == 1, "both module callbacks and external callback execute");
                check(System.getProperty(prefix + "order").equals("xba"), "Paper priority order preserved");
                fire(true);
                check(value("a") == 1 && value("b") == 2 && externalCalls == 2, "ignoreCancelled preserved");
            }).thenCompose(value -> operation("shareda", "unload"))
            .thenRun(() -> {
                check(manager.loadedIds().contains("sharedb") && count() == 1, "unloading shareda preserves sharedb registration");
                fire(false);
                check(value("a") == 1 && value("b") == 3 && externalCalls == 3, "unload preserves other module and external callback");
            });
        for (int iteration = 0; iteration < 5; iteration++) {
            int expected = 4 + iteration;
            chain = chain.thenCompose(value -> operation("sharedb", "reload")).thenRun(() -> {
                check(count() == 1, "shared listener reload has exactly one registration");
                fire(false);
                check(value("b") == expected && externalCalls == expected, "reload preserves external registration without duplicates");
            });
        }
        String registration = registration("c", "NORMAL", false);
        return chain
            .thenCompose(value -> source("collisionlistener", "module collisionlistener; enable { " + registration
                + "ctx.command(\"cesharedb\", \"\", (sender, command, label, args) -> true); }", "load")
                .handle((result, error) -> {
                    check(error != null, "post-enable preflight collision rejected");
                    check(count() == 1 && manager.loadedIds().contains("sharedb"), "unactivated candidate cleanup preserves active listener");
                    return null;
                }))
            .thenCompose(value -> source("partiallistener", "module partiallistener; enable { " + registration
                + "ctx.listen(Event.class, (Listener)System.getProperties().get(\"" + prefix + "listener\"), EventPriority.NORMAL, false, (listener, event) -> {}); }", "load")
                .handle((result, error) -> {
                    check(error != null, "partial event registration fails as expected");
                    check(count() == 1 && manager.loadedIds().contains("sharedb"), "partial activation cleanup removes only candidate registrations");
                    fire(false);
                    check(value("c") == 0 && value("b") == 9 && externalCalls == 9, "failed candidate never removes other callbacks");
                    return null;
                }))
            .thenCompose(value -> source("nulllistener", "module nulllistener; enable { ctx.listen(BlockBreakEvent.class, null, EventPriority.NORMAL, false, (listener, event) -> {}); }", "load")
                .handle((result, error) -> { check(error != null && count() == 1, "null caller Listener rejected without removing existing registration"); return null; }))
            .thenCompose(value -> operation("sharedb", "unload"))
            .thenRun(() -> {
                check(count() == 0 && manager.loadedIds().isEmpty(), "all managed registrations removed");
                fire(false);
                check(value("b") == 9 && externalCalls == 10, "last module unload leaves external registration intact");
            }).whenComplete((value, error) -> {
                HandlerList.unregisterAll(shared);
                for (String key : List.of("listener", "event", "a", "b", "c", "order")) System.getProperties().remove(prefix + key);
            });
    }
    private String module(String id, String key, String priority, boolean ignoreCancelled) {
        return "module " + id + "; enable { " + registration(key, priority, ignoreCancelled) + " }\n";
    }
    private String registration(String key, String priority, boolean ignoreCancelled) {
        return """
            ctx.listen(BlockBreakEvent.class, (Listener)System.getProperties().get("%1$slistener"), EventPriority.%2$s, %3$s, (listener, event) -> {
                if (listener != System.getProperties().get("%1$slistener") || event != System.getProperties().get("%1$sevent"))
                    throw new AssertionError("caller listener or original event replaced");
                System.setProperty("%1$s%4$s", Integer.toString(Integer.parseInt(System.getProperty("%1$s%4$s", "0")) + 1));
                System.setProperty("%1$sorder", System.getProperty("%1$sorder", "") + "%4$s");
            });
            """.formatted(prefix, priority, ignoreCancelled, key);
    }
    private void fire(boolean cancelled) {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> { throw new AssertionError("Unexpected player access: " + method.getName()); });
        var event = new BlockBreakEvent(engine.getServer().getWorlds().getFirst().getBlockAt(0, 80, 0), player);
        event.setCancelled(cancelled);
        System.getProperties().put(prefix + "event", event); System.setProperty(prefix + "order", "");
        engine.getServer().getPluginManager().callEvent(event);
    }
    private void append(String text) { System.setProperty(prefix + "order", System.getProperty(prefix + "order", "") + text); }
    private int value(String key) { return Integer.parseInt(System.getProperty(prefix + key, "0")); }
    private int count() { return HandlerList.getRegisteredListeners(engine).size(); }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed.accept(message);
    }
    private CompletableFuture<String> source(String id, String text, String operation) {
        try { Files.writeString(engine.getDataFolder().toPath().resolve("scripts").resolve(id + ".ce"), text); }
        catch (Exception e) { return CompletableFuture.failedFuture(e); }
        return operation(id, operation);
    }
    private CompletableFuture<String> operation(String id, String operation) {
        CompletableFuture<String> settled = new CompletableFuture<>();
        manager.submit(id, operation).whenComplete((value, error) -> observer.getServer().getScheduler().runTask(observer, () -> {
            if (error == null) settled.complete(value); else settled.completeExceptionally(error);
        }));
        return settled;
    }
}
