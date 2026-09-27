package kr.codenamemc.codeengine.runtime;

import java.util.List;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;

final class NativeCommand extends Command implements PluginIdentifiableCommand {
    private final Plugin plugin;
    private final CommandExecutor executor;
    NativeCommand(Plugin plugin, String name, String permission, CommandExecutor executor) {
        super(name, "Code Engine module command", "/" + name, List.of());
        this.plugin = plugin; this.executor = executor;
        if (!permission.isBlank()) setPermission(permission);
    }
    @Override public Plugin getPlugin() { return plugin; }
    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        return testPermission(sender) && executor.onCommand(sender, this, label, args);
    }
    @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) { return List.of(); }
}
