package kr.codenamemc.codeengine.externalverification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import kr.codenamemc.codeengine.runtime.ModuleManager;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable local Paper server only; deliberately disables PlaceholderAPI after verification. */
public final class ExternalVerificationPlugin extends JavaPlugin {
    private final VerificationResults results = new VerificationResults();
    private ModuleManager manager;
    private Path modules;
    private NativeExpansion nativeExpansion;
    private boolean running;
    private boolean normalStop;

    @Override public void onEnable() {
        try {
            JavaPlugin engine = (JavaPlugin) Objects.requireNonNull(getServer().getPluginManager().getPlugin("CodeEngine"));
            var managerField = engine.getClass().getDeclaredField("manager");
            managerField.setAccessible(true);
            manager = (ModuleManager) managerField.get(engine);
            modules = engine.getDataFolder().toPath().resolve("modules");
            nativeExpansion = new NativeExpansion();
            if (!nativeExpansion.register()) throw new IllegalStateException("Native expansion registration failed");
            Objects.requireNonNull(getCommand("javapapi")).setExecutor((sender, command, label, args) -> {
                int index = Integer.parseInt(args[0]);
                BenchmarkRegistry.consume(PlaceholderAPI.setPlaceholders((OfflinePlayer) null, BenchmarkRegistry.singleQuery(index)).hashCode());
                BenchmarkRegistry.commandExecuted(false);
                return true;
            });
            Objects.requireNonNull(getCommand("ceexternalverify")).setExecutor((sender, command, label, args) -> {
                if (!running) {
                    running = true;
                    if (args.length > 0) startScenario(args[0]); else start();
                }
                return true;
            });
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void start() {
        String source;
        try (var input = Objects.requireNonNull(getResource("external.ce"))) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { fail(error); return; }
        CompletableFuture<Void> chain = expectFailure("missing", "module missing; requires plugin \"DefinitelyNotInstalled\";");
        chain = chain.thenCompose(ignored -> expectFailure("undeclared", "module undeclared; use me.clip.placeholderapi.PlaceholderAPI; enable { PlaceholderAPI.getRegisteredIdentifiers(); }"));
        String failingSource = source.replace("module external;", "module failedexternal;")
            .replace("ctx.onClose(BenchmarkRegistry::clear);", "ctx.onClose(BenchmarkRegistry::clear); throw new IllegalStateException(\"expected enable failure\");");
        chain = chain.thenCompose(ignored -> expectFailure("failedexternal", failingSource))
            .thenRun(() -> verifyUnloaded());
        for (int index = 0; index < 6; index++) {
            final int loadIndex = index;
            chain = chain.thenCompose(ignored -> {
                long started = System.nanoTime();
                return submit("external", source, "load").thenAccept(value -> {
                    results.coldLoad(loadIndex, System.nanoTime() - started);
                    verifyLoaded();
                });
            }).thenCompose(ignored -> settle(manager.submit("external", "unload")))
              .thenAccept(ignored -> verifyUnloaded());
        }
        chain = chain.thenCompose(ignored -> expectFailure("undeclaredafter", "module undeclaredafter; use me.clip.placeholderapi.PlaceholderAPI; enable { PlaceholderAPI.getRegisteredIdentifiers(); }"))
            .thenCompose(ignored -> submit("external", source, "load"))
            .thenRun(() -> {
                verifyLoaded();
                verifyParity();
                captureGeneratedArtifact();
                getLogger().info("EXTERNAL LIFECYCLE PASS checks=" + results.count());
                new PairedBenchmark(this, results, this::finish, this::fail).runTaskTimer(this, 20, 1);
            });
        chain.exceptionally(error -> { fail(error); return null; });
    }
    private void verifyLoaded() {
        results.check(manager.loadedIds().contains("external"), "module loaded");
        long eventCount = BenchmarkRegistry.externalEventCount();
        getServer().getPluginManager().callEvent(new me.clip.placeholderapi.events.ExpansionRegisterEvent(nativeExpansion));
        results.check(BenchmarkRegistry.externalEventCount() == eventCount + 1, "external API event delivered once");
        results.check(BenchmarkRegistry.moduleApiClass() == PlaceholderAPI.class, "module and native share exact provider Class identity");
        results.check("value:42".equals(PlaceholderAPI.setPlaceholders((OfflinePlayer) null, "%cemodule_42%")), "provider invokes module-defined expansion callback");
        results.check("value:42".equals(PlaceholderAPI.setPlaceholders((OfflinePlayer) null, "%cenative_42%")), "native expansion remains intact");
    }
    private void verifyUnloaded() {
        results.check(!manager.loadedIds().contains("external"), "module unloaded");
        long eventCount = BenchmarkRegistry.externalEventCount();
        getServer().getPluginManager().callEvent(new me.clip.placeholderapi.events.ExpansionRegisterEvent(nativeExpansion));
        results.check(BenchmarkRegistry.externalEventCount() == eventCount, "external API listener removed");
        results.check(BenchmarkRegistry.moduleSingle() == null, "cleanup released test callback reference");
        results.check("%cemodule_42%".equals(PlaceholderAPI.setPlaceholders((OfflinePlayer) null, "%cemodule_42%")), "cleanup unregistered only module expansion");
        results.check("value:42".equals(PlaceholderAPI.setPlaceholders((OfflinePlayer) null, "%cenative_42%")), "cleanup preserved native expansion");
        results.check(getServer().getCommandMap().getCommand("codepapi") == null, "module command removed");
    }
    private void captureGeneratedArtifact() {
        try (var paths = Files.list(modules.getParent().resolve("builds"))) {
            Path build = paths.filter(path -> path.getFileName().toString().startsWith("external-")).findFirst().orElseThrow();
            Files.createDirectories(getDataFolder().toPath());
            Files.copy(build.resolve("Entry.java"), getDataFolder().toPath().resolve("generated-source.java"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(build.resolve("module.jar"), getDataFolder().toPath().resolve("module.jar"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private void verifyParity() {
        for (int index = 0; index < 256; index++) {
            results.check(NativeApiWork.single(index) == BenchmarkRegistry.moduleSingle().applyAsInt(index), "single API result parity input=" + index);
            results.check(NativeApiWork.multiple(index) == BenchmarkRegistry.moduleMultiple().applyAsInt(index), "multiple API result parity input=" + index);
            BenchmarkRegistry.consume(Long.MIN_VALUE);
            long nativeCount = BenchmarkRegistry.commandCount(false);
            results.check(getServer().dispatchCommand(getServer().getConsoleSender(), "javapapi " + index), "native command dispatched");
            results.check(BenchmarkRegistry.commandCount(false) == nativeCount + 1, "native command actually executed");
            long expected = BenchmarkRegistry.consumed();
            BenchmarkRegistry.consume(Long.MAX_VALUE);
            long moduleCount = BenchmarkRegistry.commandCount(true);
            results.check(getServer().dispatchCommand(getServer().getConsoleSender(), "codepapi " + index), "module command dispatched");
            results.check(BenchmarkRegistry.commandCount(true) == moduleCount + 1, "module command actually executed");
            results.check(expected == BenchmarkRegistry.consumed(), "command result parity input=" + index);
        }
    }
    private CompletableFuture<Void> expectFailure(String id, String source) {
        return submit(id, source, "load").handle((value, error) -> {
            results.check(error != null, "invalid dependency fails: " + id);
            results.check(!manager.loadedIds().contains(id), "invalid module stays unloaded: " + id);
            return null;
        });
    }
    private CompletableFuture<String> submit(String id, String source, String operation) {
        try { Files.writeString(modules.resolve(id + ".ce"), source); }
        catch (IOException error) { return CompletableFuture.failedFuture(error); }
        return settle(manager.submit(id, operation));
    }
    private <T> CompletableFuture<T> settle(CompletableFuture<T> operation) {
        CompletableFuture<T> settled = new CompletableFuture<>();
        operation.whenComplete((value, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error == null) settled.complete(value); else settled.completeExceptionally(error);
        }));
        return settled;
    }
    private void startScenario(String scenario) {
        if (scenario.equals("normal-stop")) { startNormalStop(); return; }
        if (scenario.equals("reentrant")) { startReentrant(); return; }
        closeNativeExpansion();
        BenchmarkRegistry.consume(0);
        String common = "module lifecycle; requires plugin \"PlaceholderAPI\"; requires plugin \"CodeEngineExternalVerification\"; "
            + "use kr.codenamemc.codeengine.externalverification.BenchmarkRegistry; ";
        String stopEngine = "ctx.server().getPluginManager().disablePlugin(ctx.plugin());";
        String stopProvider = "ctx.server().getPluginManager().disablePlugin(ctx.server().getPluginManager().getPlugin(\"PlaceholderAPI\"));";
        String continueAfterStop = "new Runnable() { public void run() { BenchmarkRegistry.consume(123456); } }.run();";
        CompletableFuture<Void> operation;
        if (scenario.equals("engine-stop")) {
            operation = expectFailure("lifecycle", common + "command lifecycleprobe { return true; } enable { "
                + "ctx.onClose(() -> BenchmarkRegistry.consume(-1)); " + stopEngine + continueAfterStop + " }");
        } else if (scenario.equals("engine-disable-hook")) {
            operation = submit("lifecycle", common + "enable { ctx.onClose(() -> BenchmarkRegistry.consume(-1)); } disable { "
                + stopEngine + continueAfterStop + " }", "load")
                .thenCompose(ignored -> settle(manager.submit("lifecycle", "unload"))).handle((value, error) -> {
                    results.check(error != null, "engine shutdown fails pending unload operation closed");
                    return null;
                });
        } else if (scenario.equals("provider-command-stop")) {
            operation = submit("lifecycle", common + "enable { ctx.onClose(() -> BenchmarkRegistry.consume(-1)); } command lifecycleprobe { "
                + stopProvider + continueAfterStop + " return true; }", "load").thenRun(() ->
                    getServer().dispatchCommand(getServer().getConsoleSender(), "lifecycleprobe"));
        } else { fail(new IllegalArgumentException("Unknown scenario: " + scenario)); return; }
        operation.thenRun(() -> getServer().getScheduler().runTaskLater(this, () -> {
            try {
                results.check(BenchmarkRegistry.consumed() == (scenario.equals("provider-command-stop") ? -1 : 123456),
                    "running callback completed before cleanup; local cleanup survives provider stop");
                results.check(!manager.loadedIds().contains("lifecycle"), "stopped candidate or module is not active");
                results.check(getServer().getCommandMap().getCommand("lifecycleprobe") == null, "stopped module command absent");
                awaitArtifactRemoval(100);
            } catch (Throwable error) { fail(error); }
        }, 2L)).exceptionally(error -> { fail(error); return null; });
    }
    private void awaitArtifactRemoval(int remainingTicks) {
        try (var paths = Files.list(modules.getParent().resolve("builds"))) {
            boolean removed = paths.noneMatch(path -> path.getFileName().toString().startsWith("lifecycle-"));
            if (removed) {
                results.check(true, "artifact deleted after callback returned");
                complete();
            } else if (remainingTicks > 0) {
                getServer().getScheduler().runTaskLater(this, () -> awaitArtifactRemoval(remainingTicks - 1), 1L);
            } else {
                fail(new AssertionError("Generated artifact was not retired after callback return"));
            }
        } catch (IOException error) { fail(error); }
    }
    private void startNormalStop() {
        normalStop = true;
        String source = """
            module normalstop;
            requires plugin "PlaceholderAPI";
            use me.clip.placeholderapi.PlaceholderAPI;
            use java.nio.file.Files;
            enable {
                PlaceholderAPI.setPlaceholders((OfflinePlayer) null, "ready");
                Files.writeString(ctx.dataDirectory().resolve("enable.marker"), "enabled");
                ctx.onClose(() -> Files.writeString(ctx.dataDirectory().resolve("cleanup.marker"), "cleaned"));
                ctx.onPluginClose("PlaceholderAPI", () -> Files.writeString(ctx.dataDirectory().resolve("provider-cleanup.marker"), "cleaned"));
            }
            disable { Files.writeString(ctx.dataDirectory().resolve("disable.marker"), "disabled"); }
            """;
        submit("normalstop", source, "load").thenRun(() -> {
            try {
                results.check(manager.loadedIds().contains("normalstop"), "module active before ordinary server stop");
                results.write(getDataFolder().toPath());
                getLogger().info("EXTERNAL NORMAL STOP READY");
            } catch (IOException error) { fail(error); }
        }).exceptionally(error -> { fail(error); return null; });
    }
    private void startReentrant() {
        closeNativeExpansion();
        String source = """
            module reentrant;
            requires plugin "PlaceholderAPI";
            command reentrantprobe { return true; }
            enable {
                ctx.server().getPluginManager().disablePlugin(ctx.server().getPluginManager().getPlugin("PlaceholderAPI"));
            }
            """;
        expectFailure("reentrant", source).thenRun(() -> {
            results.check(getServer().getCommandMap().getCommand("reentrantprobe") == null,
                "provider stopped during enable cannot publish candidate command");
            complete();
        }).exceptionally(error -> { fail(error); return null; });
    }
    private void finish() {
        closeNativeExpansion();
        var provider = Objects.requireNonNull(getServer().getPluginManager().getPlugin("PlaceholderAPI"));
        getServer().getPluginManager().disablePlugin(provider);
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                results.check(!manager.loadedIds().contains("external"), "provider disable invalidated dependent module");
                results.check(BenchmarkRegistry.moduleSingle() == null, "provider disable still releases independent resources");
                results.check(getServer().getCommandMap().getCommand("codepapi") == null, "provider disable removed module command");
                BenchmarkRegistry.clear();
                getServer().getPluginManager().enablePlugin(provider);
                results.check(provider.isEnabled(), "provider re-enabled for stale generation check");
                expectFailure("stale", "module stale; requires plugin \"PlaceholderAPI\";")
                    .thenRun(this::complete).exceptionally(error -> { fail(error); return null; });
            } catch (Throwable error) { fail(error); }
        }, 2L);
    }
    private void complete() {
        try {
            results.write(getDataFolder().toPath());
            getLogger().info("EXTERNAL VERIFICATION PASS checks=" + results.count());
        } catch (IOException error) { fail(error); }
    }
    private void fail(Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE, "EXTERNAL VERIFICATION FAILED", error);
        try { results.write(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("failure.txt"), error.toString()); }
        catch (IOException writeError) { getLogger().log(java.util.logging.Level.SEVERE, "Writing failure report failed", writeError); }
    }
    private void closeNativeExpansion() {
        if (nativeExpansion != null) {
            nativeExpansion.unregister();
            nativeExpansion = null;
        }
    }
    @Override public void onDisable() {
        if (normalStop) {
            try {
                boolean providerEnabled = Objects.requireNonNull(getServer().getPluginManager().getPlugin("PlaceholderAPI")).isEnabled();
                boolean moduleStillLoaded = manager.loadedIds().contains("normalstop");
                Files.writeString(getDataFolder().toPath().resolve("native-shutdown.txt"),
                    "providerEnabled=" + providerEnabled + "\nmoduleStillLoaded=" + moduleStillLoaded + "\n");
            } catch (IOException error) { getLogger().log(java.util.logging.Level.SEVERE, "Native shutdown probe failed", error); }
        }
        closeNativeExpansion();
        BenchmarkRegistry.clear();
    }
}
