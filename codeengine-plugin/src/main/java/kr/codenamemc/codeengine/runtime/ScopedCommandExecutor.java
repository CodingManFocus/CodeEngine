package kr.codenamemc.codeengine.runtime;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Allocated once at registration; keeps reentrant unload from closing an executing command. */
final class ScopedCommandExecutor implements CommandExecutor {
    private final EventActivity activity;
    private final CommandExecutor executor;

    ScopedCommandExecutor(EventActivity activity, CommandExecutor executor) {
        this.activity = activity;
        this.executor = executor;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!activity.enter(false)) return false;
        try { return executor.onCommand(sender, command, label, args); }
        finally { activity.leave(false); }
    }
}
