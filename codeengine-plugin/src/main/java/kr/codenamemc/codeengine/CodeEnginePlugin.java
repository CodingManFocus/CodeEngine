package kr.codenamemc.codeengine;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import javax.tools.ToolProvider;
import org.bukkit.plugin.java.JavaPlugin;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.compiler.ModuleCompiler;
import kr.codenamemc.codeengine.runtime.*;
import kr.codenamemc.codeengine.workspace.ModuleSourceStore;

public final class CodeEnginePlugin extends JavaPlugin  {
    private ModuleSourceStore store;
    private ModuleManager manager;
    private kr.codenamemc.codeengine.command.EngineCommands commands;
    @Override public void onEnable() {
        try {
            if (ToolProvider.getSystemJavaCompiler() == null) throw new IllegalStateException("Use a full JDK 21+, not a JRE");
            Path root = getDataFolder().toPath();
            boolean firstInstall = Files.notExists(root);
            saveDefaultConfig();
            store = new ModuleSourceStore(root.resolve("modules"));
            if (firstInstall && getConfig().getBoolean("seedExample", true) && store.list().isEmpty()) {
                try (var input = getResource("examples/hello.ce")) {
                    if (input == null) throw new IllegalStateException("Missing bundled example");
                    store.save("hello", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), "new");
                }
            }
            String classpath = RuntimeClasspath.collect(CodeModule.class, org.bukkit.Bukkit.class,
                net.kyori.adventure.text.Component.class, com.google.gson.Gson.class);
            manager = new ModuleManager(this, store, new ModuleCompiler(root.resolve("builds"), classpath));
            commands = new kr.codenamemc.codeengine.command.EngineCommands(this, store, manager);
            Objects.requireNonNull(getCommand("codeengine")).setExecutor(commands);
            Objects.requireNonNull(getCommand("codeengine")).setTabCompleter(commands);
            if (getConfig().getBoolean("autoLoad", true)) {
                java.util.concurrent.CompletableFuture<Void> chain = java.util.concurrent.CompletableFuture.completedFuture(null);
                for (String id : store.list()) chain = chain.thenCompose(ignored -> manager.submit(id, "load").handle((value, error) -> {
                    if (error == null) getLogger().info(value); else getLogger().log(Level.WARNING, "Autoload failed: " + id, error);
                    return (Void) null;
                }));
            }
            getLogger().info("Code Engine ready. WebIDE is stopped; use /codeengine webide from the server console.");
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.SEVERE, "Code Engine startup failed", e); getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if (commands != null) commands.close();
        if (manager != null) manager.close();
    }
}
