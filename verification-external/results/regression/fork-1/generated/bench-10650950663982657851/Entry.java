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
ctx.command("cebenchcalc", "", this::__ceCommand2);
ctx.command("cebenchblocks", "", this::__ceCommand3);
}
@Override public void enable() throws Exception {
world = ctx.server().getWorlds().getFirst(); 

}
private boolean __ceCommand2(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
long value = Long.parseLong(args[0]);
    for (int i = 0; i < 1024; i++) value = (value * 1664525L + 1013904223L) & 0xffffffffL;
    sender.sendMessage(Long.toString(value)); return true;


}
private boolean __ceCommand3(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
int seed = Integer.parseInt(args[0]), sum = 0;
    for (int i = 0; i < 256; i++) if (world.getBlockAt((i + seed) & 15, 80, i >>> 4).getType() == Material.STONE) sum += i;
    sender.sendMessage(Integer.toString(sum)); return true;


}
}
