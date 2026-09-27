package kr.codenamemc.codeengine.externalverification;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

final class PairedBenchmark extends BukkitRunnable {
    private static final int warmupTicks = 160;
    private static final int measuredTicks = 240;
    private static final int apiOperations = 1024;
    private static final int commandOperations = 128;
    private final JavaPlugin plugin;
    private final VerificationResults results;
    private final Runnable completed;
    private final java.util.function.Consumer<Throwable> failed;
    private final Random order = new Random(20260927);
    private final ThreadMXBean allocationBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final IntUnaryOperator[] nativeOperations = {NativeApiWork::single, NativeApiWork::multiple};
    private final IntUnaryOperator[] moduleOperations = {BenchmarkRegistry.moduleSingle(), BenchmarkRegistry.moduleMultiple()};
    private final String[][] commands = new String[2][256];
    private final CommandSender sender;
    private int tick;

    PairedBenchmark(JavaPlugin plugin, VerificationResults results, Runnable completed,
                    java.util.function.Consumer<Throwable> failed) {
        this.plugin = plugin;
        this.results = results;
        this.completed = completed;
        this.failed = failed;
        sender = plugin.getServer().getConsoleSender();
        if (!allocationBean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation measurements unavailable");
        allocationBean.setThreadAllocatedMemoryEnabled(true);
        for (int index = 0; index < 256; index++) {
            commands[0][index] = "javapapi " + index;
            commands[1][index] = "codepapi " + index;
        }
    }
    @Override public void run() {
        try {
            int firstWorkload = tick & 1;
            for (int offset = 0; offset < 2; offset++) {
                int workload = (firstWorkload + offset) & 1;
                boolean moduleFirst = order.nextBoolean();
                measureApi(workload, moduleFirst);
                measureApi(workload, !moduleFirst);
            }
            boolean moduleFirst = order.nextBoolean();
            measureCommands(moduleFirst);
            measureCommands(!moduleFirst);
            if (++tick == warmupTicks + measuredTicks) {
                cancel();
                completed.run();
            }
        } catch (Throwable error) {
            cancel();
            failed.accept(error);
        }
    }
    private void measureApi(int workload, boolean module) {
        IntUnaryOperator operation = (module ? moduleOperations : nativeOperations)[workload];
        long thread = Thread.currentThread().threadId();
        long bytes = allocationBean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        long sum = 0;
        for (int index = 0; index < apiOperations; index++) sum += operation.applyAsInt(index + tick);
        long elapsed = System.nanoTime() - start;
        long allocated = allocationBean.getThreadAllocatedBytes(thread) - bytes;
        BenchmarkRegistry.consume(sum);
        if (tick >= warmupTicks) results.sample(tick - warmupTicks, module ? "module" : "native",
            workload == 0 ? "apiSingle" : "apiMultiple", elapsed, allocated, apiOperations);
    }
    private void measureCommands(boolean module) {
        int path = module ? 1 : 0;
        long thread = Thread.currentThread().threadId();
        long bytes = allocationBean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        long sum = 0;
        for (int index = 0; index < commandOperations; index++) {
            plugin.getServer().dispatchCommand(sender, commands[path][(index + tick) & 255]);
            sum += BenchmarkRegistry.consumed();
        }
        long elapsed = System.nanoTime() - start;
        long allocated = allocationBean.getThreadAllocatedBytes(thread) - bytes;
        BenchmarkRegistry.consume(sum);
        if (tick >= warmupTicks) results.sample(tick - warmupTicks, module ? "module" : "native",
            "commandSingle", elapsed, allocated, commandOperations);
    }
}
