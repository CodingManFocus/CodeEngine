package kr.codenamemc.codeengine.verification;

import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import kr.codenamemc.codeengine.runtime.ModuleManager;

/** Real Paper regression checks for stop-then-start module replacement. */
final class ReloadVerification {
    private final JavaPlugin observer;
    private final JavaPlugin engine;
    private final ModuleManager manager;
    private final Consumer<String> passed;
    private final Path scripts;
    ReloadVerification(JavaPlugin observer, JavaPlugin engine, ModuleManager manager, Consumer<String> passed) {
        this.observer = observer; this.engine = engine; this.manager = manager; this.passed = passed;
        scripts = engine.getDataFolder().toPath().resolve("scripts");
    }
    CompletableFuture<Void> run() {
        return persistedStateAndLock().thenCompose(value -> failedDisable())
            .thenCompose(value -> failedBuildAndReload()).thenCompose(value -> cancelledTimer())
            .thenCompose(value -> busyOperation());
    }
    private CompletableFuture<Void> persistedStateAndLock() {
        String source = """
            module reloadstate;
            state int count = 0;
            state java.nio.channels.FileChannel channel;
            state java.nio.channels.FileLock lock;
            enable {
                var file = ctx.dataDirectory().resolve("count.txt");
                if (java.nio.file.Files.exists(file)) count = Integer.parseInt(java.nio.file.Files.readString(file));
                channel = java.nio.channels.FileChannel.open(ctx.dataDirectory().resolve("owner.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                lock = channel.tryLock();
                if (lock == null) throw new IllegalStateException("lock unavailable");
                java.nio.file.Files.writeString(ctx.dataDirectory().resolve("order.txt"), "E", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            command cereloadstate { count++; return true; }
            disable {
                java.nio.file.Files.writeString(ctx.dataDirectory().resolve("count.txt"), Integer.toString(count));
                if (lock != null) lock.release();
                if (channel != null) channel.close();
                java.nio.file.Files.writeString(ctx.dataDirectory().resolve("order.txt"), "D", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            """;
        return source("reloadstate", source, "load").thenRun(() -> {
            engine.getServer().dispatchCommand(engine.getServer().getConsoleSender(), "cereloadstate");
        }).thenCompose(value -> operation("reloadstate", "reload")).thenRun(() -> {
            check(read("reloadstate", "order.txt").equals("EDE"), "old disable precedes new enable; exclusive lock reacquired");
        }).thenCompose(value -> operation("reloadstate", "unload")).thenRun(() -> {
            check(read("reloadstate", "count.txt").equals("1"), "reload preserves saved count through next unload");
            check(read("reloadstate", "order.txt").equals("EDED"), "each instance enabled and disabled exactly once");
            stopped("reloadstate", "cereloadstate");
        });
    }
    private CompletableFuture<Void> failedDisable() {
        String old = """
            module reloadstop;
            every 1 ticks {}
            command cereloadstop { return true; }
            disable { throw new AssertionError("stop-before-load"); }
            """;
        return source("reloadstop", old, "load")
            .thenCompose(value -> operation("reloadstop", "unload").handle((result, error) -> {
                check(error != null && cause(error).contains("stop-before-load"), "unload reports disable failure");
                stopped("reloadstop", "cereloadstop"); return null;
            })).thenCompose(value -> source("reloadstop", old, "load"))
            // A compile error must never supersede the earlier disable error.
            .thenCompose(value -> source("reloadstop", "module reloadstop; enable { missingSymbol(); }", "reload")
                .handle((result, error) -> {
                    check(error != null && cause(error).contains("stop-before-load"), "disable failure stops reload before compilation");
                    stopped("reloadstop", "cereloadstop"); return null;
                }))
            .thenCompose(value -> source("reloadstop", old, "load"))
            .thenCompose(value -> source("reloadstop", "module reloadstop; enable { java.nio.file.Files.writeString(ctx.dataDirectory().resolve(\"new-enable.txt\"), \"ran\"); }", "reload")
                .handle((result, error) -> {
                    check(error != null && !Files.exists(data("reloadstop").resolve("new-enable.txt")), "disable failure prevents replacement enable");
                    stopped("reloadstop", "cereloadstop"); return null;
                }))
            .thenCompose(value -> source("reloadstop", "module reloadstop;", "load"))
            .thenCompose(value -> operation("reloadstop", "unload")).thenApply(value -> null);
    }
    private CompletableFuture<Void> failedBuildAndReload() {
        String good = "module reloadfailure; command cereloadfailure { return true; } every 1 ticks {} on BlockBreakEvent e {}";
        return source("reloadfailure", good, "load")
            .thenCompose(value -> source("reloadfailure", "module reloadfailure; enable { missingSymbol(); }", "build")
                .handle((result, error) -> {
                    check(error != null && manager.loadedIds().contains("reloadfailure"), "build failure preserves running module"); return null;
                }))
            .thenCompose(value -> operation("reloadfailure", "reload").handle((result, error) -> {
                check(error != null, "reload compile error reported"); stopped("reloadfailure", "cereloadfailure"); return null;
            }))
            .thenCompose(value -> source("reloadfailure", good, "reload"))
            .thenRun(() -> check(manager.loadedIds().contains("reloadfailure"), "reload of stopped module loads it"))
            .thenCompose(value -> source("reloadfailure", "module reloadfailure; state int bad = Integer.parseInt(\"bad\");", "reload")
                .handle((result, error) -> {
                    check(error != null, "constructor error reported"); stopped("reloadfailure", "cereloadfailure"); return null;
                }))
            .thenCompose(value -> source("reloadfailure", good, "load"))
            .thenRun(() -> { try { Files.delete(scripts.resolve("reloadfailure.ce")); } catch (Exception e) { throw new RuntimeException(e); } })
            .thenCompose(value -> operation("reloadfailure", "reload").handle((result, error) -> {
                check(error != null, "missing source reported after unload"); stopped("reloadfailure", "cereloadfailure"); return null;
            }));
    }
    private CompletableFuture<Void> cancelledTimer() {
        String source = """
            module reloadtimer;
            every 1 ticks {
                System.setProperty("codeengine.verification.timer", Integer.toString(Integer.parseInt(System.getProperty("codeengine.verification.timer", "0")) + 1));
                throw new IllegalStateException("expected timer failure");
            }
            command cereloadtimer { return true; }
            """;
        System.clearProperty("codeengine.verification.timer");
        return source("reloadtimer", source, "load").thenCompose(value -> ticks(4))
            .thenRun(() -> check("1".equals(System.getProperty("codeengine.verification.timer")), "failed timer executed once"))
            .thenCompose(value -> source("reloadtimer", "module reloadtimer; on Event e {}", "reload")
                .handle((result, error) -> { check(error != null, "invalid event replacement rejected"); return null; }))
            .thenCompose(value -> ticks(4)).thenRun(() -> {
                check("1".equals(System.getProperty("codeengine.verification.timer")), "failed reload never resurrects cancelled timer");
                stopped("reloadtimer", "cereloadtimer"); System.clearProperty("codeengine.verification.timer");
            });
    }
    private CompletableFuture<Void> busyOperation() {
        return source("reloadbusy", "module reloadbusy;", "load").thenCompose(value -> {
            CompletableFuture<String> reload = manager.submit("reloadbusy", "reload");
            CompletableFuture<String> duplicate = manager.submit("reloadbusy", "load");
            check(duplicate.isCompletedExceptionally(), "busy guard covers complete unload-to-load operation");
            return settle(reload);
        }).thenCompose(value -> operation("reloadbusy", "unload")).thenRun(() -> {
            check(manager.loadedIds().isEmpty(), "all reload regression modules stopped");
            try (var paths = Files.list(engine.getDataFolder().toPath().resolve("builds"))) {
                check(paths.findAny().isEmpty(), "no abandoned build directories after failures");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private void stopped(String id, String command) {
        check(!manager.loadedIds().contains(id), "stopped module absent from loaded list: " + id);
        check(engine.getServer().getCommandMap().getCommand(command) == null, "command absent: " + command);
        check(engine.getServer().getCommandMap().getCommand("codeengine_" + id + ":" + command) == null, "namespaced command absent: " + command);
        check(HandlerList.getRegisteredListeners(engine).isEmpty(), "no lingering event listener");
        check(engine.getServer().getScheduler().getPendingTasks().stream().noneMatch(task -> task.getOwner() == engine), "no lingering engine task");
    }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed.accept(message);
    }
    private Path data(String id) { return engine.getDataFolder().toPath().resolve("data").resolve(id); }
    private String read(String id, String filename) {
        try { return Files.readString(data(id).resolve(filename)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private String cause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.toString();
    }
    private CompletableFuture<String> source(String id, String text, String operation) {
        try { Files.writeString(scripts.resolve(id + ".ce"), text); }
        catch (Exception e) { return CompletableFuture.failedFuture(e); }
        return operation(id, operation);
    }
    private CompletableFuture<String> operation(String id, String operation) { return settle(manager.submit(id, operation)); }
    private <T> CompletableFuture<T> settle(CompletableFuture<T> operation) {
        CompletableFuture<T> settled = new CompletableFuture<>();
        operation.whenComplete((value, error) -> observer.getServer().getScheduler().runTask(observer, () -> {
            if (error == null) settled.complete(value); else settled.completeExceptionally(error);
        }));
        return settled;
    }
    private CompletableFuture<Void> ticks(long delay) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        observer.getServer().getScheduler().runTaskLater(observer, () -> result.complete(null), delay);
        return result;
    }
}
