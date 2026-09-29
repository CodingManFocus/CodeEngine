package kr.codenamemc.codeengine.command;

import java.util.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import kr.codenamemc.codeengine.runtime.ModuleManager;
import kr.codenamemc.codeengine.workspace.ModuleSourceStore;
import kr.codenamemc.codeengine.web.WebIdeServer;

public final class EngineCommands implements TabExecutor, AutoCloseable {
    private final JavaPlugin plugin;
    private final ModuleSourceStore store;
    private final ModuleManager manager;
    private WebIdeServer web;
    public EngineCommands(JavaPlugin plugin, ModuleSourceStore store, ModuleManager manager) {
        this.plugin = plugin; this.store = store; this.manager = manager;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("codeengine.admin")) { sender.sendMessage("Missing codeengine.admin permission"); return true; }
        if (args.length == 0) { help(sender); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "list" -> {
                    sender.sendMessage("Loaded modules: " + String.join(", ", new TreeSet<>(manager.loadedIds())));
                    if (!manager.stoppingIds().isEmpty()) sender.sendMessage("Stopping modules: " + String.join(", ", new TreeSet<>(manager.stoppingIds())));
                }
                case "webide" -> {
                    if (!(sender instanceof ConsoleCommandSender)) { sender.sendMessage("Open WebIDE from the server console."); return true; }
                    if (web == null) web = new WebIdeServer(store, manager::submit, manager::loadedIds, plugin.getConfig().getInt("webPort", 17777));
                    sender.sendMessage("WebIDE (private session): " + web.url());
                }
                case "webstop" -> { if (web != null) { web.close(); web = null; } sender.sendMessage("WebIDE stopped"); }
                case "build", "load", "reload", "unload" -> {
                    if (args.length != 2) { help(sender); return true; }
                    String operation = args[0].toLowerCase(Locale.ROOT);
                    sender.sendMessage("Queued " + operation + ": " + args[1]);
                    manager.submit(args[1], operation).whenComplete((value, error) -> {
                        if (!plugin.isEnabled()) return;
                        try {
                            plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(error == null ? value : "Failed: " + error.getMessage()));
                        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Server shutdown won the race. */ }
                    });
                }
                default -> help(sender);
            }
        } catch (Exception e) { sender.sendMessage("Failed: " + e.getMessage()); }
        return true;
    }
    private void help(CommandSender sender) { sender.sendMessage("/ce list | build/load/reload/unload <module> | webide | webstop"); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("codeengine.admin")) return List.of();
        List<String> choices = args.length == 1 ? List.of("list", "build", "load", "reload", "unload", "webide", "webstop") : List.of();
        if (args.length == 2) choices = new ArrayList<>(manager.loadedIds());
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.startsWith(prefix)).sorted().toList();
    }
    @Override public void close() { if (web != null) { web.close(); web = null; } }
}
