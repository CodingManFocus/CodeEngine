package kr.codenamemc.codeengine.verification;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import kr.codenamemc.codeengine.runtime.ModuleManager;

/** Isolated test server only. Never install on a production server. */
public final class VerificationPlugin extends JavaPlugin {
    private JavaPlugin engine;
    private ModuleManager manager;
    private Path modules;
    private World world;
    private volatile String response;
    private CommandSender capture;
    private boolean running;
    private int checks;
    private final StringBuilder report = new StringBuilder();
    @Override public void onEnable() {
        try {
            engine = (JavaPlugin) getServer().getPluginManager().getPlugin("CodeEngine");
            var field = engine.getClass().getDeclaredField("manager"); field.setAccessible(true); manager = (ModuleManager) field.get(engine);
            modules = engine.getDataFolder().toPath().resolve("modules");
            capture = getServer().createCommandSender(component -> response = PlainTextComponentSerializer.plainText().serialize(component));
            Objects.requireNonNull(getCommand("ceverify")).setExecutor((sender, command, label, args) -> { if (!running) { running = true; start(); } return true; });
            Objects.requireNonNull(getCommand("nativecalc")).setExecutor((sender, command, label, args) -> {
                long value = Long.parseLong(args[0]);
                for (int i = 0; i < 1024; i++) value = (value * 1664525L + 1013904223L) & 0xffffffffL;
                sender.sendMessage(Long.toString(value)); return true;
            });
            Objects.requireNonNull(getCommand("nativeblocks")).setExecutor((sender, command, label, args) -> {
                int seed = Integer.parseInt(args[0]), sum = 0;
                for (int i = 0; i < 256; i++) if (world.getBlockAt((i + seed) & 15, 80, i >>> 4).getType() == Material.STONE) sum += i;
                sender.sendMessage(Integer.toString(sum)); return true;
            });
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void start() {
        world = getServer().getWorlds().getFirst();
        for (int i = 0; i < 256; i++) world.getBlockAt(i & 15, 80, i >>> 4).setType((i % 3 == 0) ? Material.STONE : Material.DIRT, false);
        String good = """
            module probe;
            state int calls = 0;
            state int ticks = 0;
            on BlockBreakEvent e {
                calls++;
                long x = e.getBlock().getX(), z = e.getBlock().getZ();
                if (x*x + z*z <= 256L) e.setCancelled(true);
            }
            every 1 ticks { ticks++; }
            command ceprobe { sender.sendMessage(calls + ":" + ticks); return true; }
            """;
        CompletableFuture<Void> chain = submitSource("probe", good, "load").thenAccept(value -> {
            check(manager.loadedIds().contains("probe"), "module load"); verifyEvents(1); check(activeTasks() == 1, "one module task");
        });
        for (int iteration = 0; iteration < 20; iteration++) {
            chain = chain.thenCompose(value -> submitSource("probe", good, "reload")).thenAccept(value -> {
                verifyEvents(1); check(activeTasks() == 1, "no task duplication after reload");
                check(ListenerAssertions.managedListeners(engine).size() == 1, "no listener duplication");
            });
        }
        chain = chain.thenCompose(value -> expectFailure("module probe; command ceprobe { doesNotExist(); return true; }"))
            .thenCompose(value -> submitSource("probe", good, "load"))
            .thenCompose(value -> expectFailure("module probe; command ceprobe { return true; } enable { throw new AssertionError(\"expected activation failure\"); }"))
            .thenCompose(value -> submitSource("probe", good, "load"))
            .thenCompose(value -> expectFailure("module probe; on Event e {} command ceprobe { return true; }"))
            .thenCompose(value -> submitSource("probe", good, "load"))
            .thenCompose(value -> submitSource("other", "module other; command ceprobe { return true; }", "load").handle((result, error) -> {
                check(error != null, "command collision rejected"); verifyEvents(1); return null;
            }))
            .thenCompose(value -> settle(manager.submit("probe", "unload")))
            .thenRun(() -> {
                check(!manager.loadedIds().contains("probe"), "module unload");
                check(getServer().getCommandMap().getCommand("ceprobe") == null, "command removed");
                check(getServer().getCommandMap().getCommand("codeengine_probe:ceprobe") == null, "namespaced command removed");
                check(ListenerAssertions.managedListeners(engine).isEmpty(), "listeners removed");
                check(activeTasks() == 0, "tasks cancelled");
            })
            .thenCompose(value -> new ReloadVerification(this, engine, manager, message -> check(true, message)).run())
            .thenCompose(value -> new ListenerOwnershipVerification(this, engine, manager, message -> check(true, message)).run())
            .thenCompose(value -> new AsyncLifecycleVerification(this, engine, manager, message -> check(true, message)).run())
            .thenCompose(value -> submitSource("bench", benchmarkSource(), "load"))
            .thenRun(this::startBenchmark);
        chain.exceptionally(error -> { fail(error); return null; });
    }
    private CompletableFuture<String> submitSource(String id, String source, String operation) {
        try { Files.writeString(modules.resolve(id + ".ce"), source); }
        catch (java.io.IOException e) { return CompletableFuture.failedFuture(e); }
        return settle(manager.submit(id, operation));
    }
    private <T> CompletableFuture<T> settle(CompletableFuture<T> operation) {
        CompletableFuture<T> settled = new CompletableFuture<>();
        operation.whenComplete((value, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error == null) settled.complete(value); else settled.completeExceptionally(error);
        }));
        return settled;
    }
    private CompletableFuture<Void> expectFailure(String source) {
        return submitSource("probe", source, "reload").handle((result, error) -> {
            check(error != null, "invalid replacement fails");
            check(!manager.loadedIds().contains("probe"), "failed reload leaves module stopped");
            check(activeTasks() == 0, "failed reload leaves no timer");
            check(ListenerAssertions.managedListeners(engine).isEmpty(), "failed reload leaves no listener");
            check(getServer().getCommandMap().getCommand("ceprobe") == null, "failed reload removes old command");
            check(getServer().getCommandMap().getCommand("codeengine_probe:ceprobe") == null, "failed reload removes old namespaced command");
            return null;
        });
    }
    private long activeTasks() { return getServer().getScheduler().getPendingTasks().stream().filter(task -> task.getOwner() == engine).count(); }
    private void verifyEvents(int expectedCalls) {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> { throw new AssertionError("Unexpected player access: " + method.getName()); });
        var event = new BlockBreakEvent(world.getBlockAt(1, 80, 1), player);
        getServer().getPluginManager().callEvent(event);
        check(event.isCancelled(), "real Paper event cancellation");
        response = ""; getServer().dispatchCommand(capture, "ceprobe");
        check(response.startsWith(expectedCalls + ":"), "exactly one event handler invocation");
    }
    private String benchmarkSource() {
        return """
            module bench;
            state World world;
            enable { world = ctx.server().getWorlds().getFirst(); }
            command cebenchcalc {
                long value = Long.parseLong(args[0]);
                for (int i = 0; i < 1024; i++) value = (value * 1664525L + 1013904223L) & 0xffffffffL;
                sender.sendMessage(Long.toString(value)); return true;
            }
            command cebenchblocks {
                int seed = Integer.parseInt(args[0]), sum = 0;
                for (int i = 0; i < 256; i++) if (world.getBlockAt((i + seed) & 15, 80, i >>> 4).getType() == Material.STONE) sum += i;
                sender.sendMessage(Integer.toString(sum)); return true;
            }
            """;
    }
    private void startBenchmark() {
        for (int seed = 0; seed < 64; seed++) for (String task : List.of("calc", "blocks")) {
            getServer().dispatchCommand(capture, "native" + task + " " + seed); String nativeValue = response;
            getServer().dispatchCommand(capture, "cebench" + task + " " + seed); check(nativeValue.equals(response), "matching " + task + " result seed=" + seed);
        }
        List<Sample> samples = new ArrayList<>();
        String[][] commands = new String[4][128];
        String[] names = {"nativecalc", "cebenchcalc", "nativeblocks", "cebenchblocks"};
        for (int task = 0; task < 4; task++) for (int i = 0; i < 128; i++) commands[task][i] = names[task] + " " + (i & 63);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        getLogger().info("LIFECYCLE PASS checks=" + checks + "; starting paired benchmark");
        new BukkitRunnable() {
            int tick;
            final Random order = new Random(20260925);
            @Override public void run() {
                try {
                    List<Integer> tasks = new ArrayList<>(List.of(0,1,2,3)); Collections.shuffle(tasks, order);
                    for (int task : tasks) {
                        long thread = Thread.currentThread().threadId(), bytes = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
                        for (String command : commands[task]) getServer().dispatchCommand(capture, command);
                        long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(thread) - bytes;
                        if (tick >= 120) samples.add(new Sample(tick - 120, names[task], elapsed / 128.0, allocated / 128.0));
                    }
                    if (++tick == 240) { cancel(); finish(samples); }
                } catch (Throwable error) { cancel(); fail(error); }
            }
        }.runTaskTimer(this, 20, 1);
    }
    private void finish(List<Sample> samples) throws Exception {
        Files.createDirectories(getDataFolder().toPath());
        var csv = new StringBuilder("sample,task,nsPerOperation,bytesPerOperation\n");
        for (Sample sample : samples) csv.append(sample.index()).append(',').append(sample.task()).append(',').append(sample.ns()).append(',').append(sample.bytes()).append('\n');
        Files.writeString(getDataFolder().toPath().resolve("samples.csv"), csv);
        Files.writeString(getDataFolder().toPath().resolve("checks.txt"), "PASS " + checks + " checks\n" + report);
        getLogger().info("VERIFICATION PASS checks=" + checks + " samples=" + samples.size());
    }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); checks++; report.append("PASS ").append(message).append('\n');
    }
    private void fail(Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE, "VERIFICATION FAILED", error);
        try { Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("failure.txt"), error.toString()); }
        catch (java.io.IOException e) { getLogger().log(java.util.logging.Level.SEVERE, "Could not write failure", e); }
    }
    private record Sample(int index, String task, double ns, double bytes) { }
}
