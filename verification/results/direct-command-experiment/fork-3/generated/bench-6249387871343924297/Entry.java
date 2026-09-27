package kr.codenamemc.codeengine.generated.m_bench;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import net.kyori.adventure.text.Component;
import java.util.*;
public final class Entry implements kr.codenamemc.codeengine.api.CodeModule, Listener {
private kr.codenamemc.codeengine.api.ModuleContext ctx;
private World world
;
@Override public void prepare(kr.codenamemc.codeengine.api.ModuleContext context) {
this.ctx = context;
ctx.command(new __ceNativeCommand2());
ctx.command(new __ceNativeCommand3());
}
@Override public void enable() throws Exception {
world = ctx.server().getWorlds().getFirst(); 

}
private boolean __ceCommand2(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
long value = Long.parseLong(args[0]);
    for (int i = 0; i < 1024; i++) value = (value * 1664525L + 1013904223L) & 0xffffffffL;
    sender.sendMessage(Long.toString(value)); return true;


}
private final class __ceNativeCommand2 extends org.bukkit.command.Command implements org.bukkit.command.PluginIdentifiableCommand {
private __ceNativeCommand2() { super("cebenchcalc");}
@Override public org.bukkit.plugin.Plugin getPlugin() { return ctx.plugin(); }
@Override public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
if (!testPermission(sender)) return true; return __ceCommand2(sender, this, label, args);
}
@Override public java.util.List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) { return java.util.List.of(); }
}
private boolean __ceCommand3(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
int seed = Integer.parseInt(args[0]), sum = 0;
    for (int i = 0; i < 256; i++) if (world.getBlockAt((i + seed) & 15, 80, i >>> 4).getType() == Material.STONE) sum += i;
    sender.sendMessage(Integer.toString(sum)); return true;


}
private final class __ceNativeCommand3 extends org.bukkit.command.Command implements org.bukkit.command.PluginIdentifiableCommand {
private __ceNativeCommand3() { super("cebenchblocks");}
@Override public org.bukkit.plugin.Plugin getPlugin() { return ctx.plugin(); }
@Override public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
if (!testPermission(sender)) return true; return __ceCommand3(sender, this, label, args);
}
@Override public java.util.List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) { return java.util.List.of(); }
}
}
