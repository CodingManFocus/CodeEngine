package kr.codenamemc.codeengine.compiler;

import java.util.HashMap;
import java.util.Map;
import static kr.codenamemc.codeengine.compiler.ModuleAst.*;

/** No runtime interpreter, object model, event DTO or reflective API dispatch. */
public final class JavaEmitter {
    private final StringBuilder out = new StringBuilder();
    private final Map<Integer, Integer> lineMap = new HashMap<>();
    private int line = 1;
    public GeneratedSource emit(ModuleAst module) {
        String packageName = "kr.codenamemc.codeengine.generated.m_" + module.id();
        add("package " + packageName + ";\n");
        add("import org.bukkit.*;\nimport org.bukkit.entity.*;\nimport org.bukkit.event.*;\n");
        add("import org.bukkit.event.player.*;\nimport org.bukkit.event.block.*;\n");
        add("import net.kyori.adventure.text.Component;\nimport java.util.*;\n");
        for (Import imported : module.imports()) {
            lineMap.put(line, imported.line());
            add("import " + imported.type() + ";\n");
        }
        add("public final class Entry implements kr.codenamemc.codeengine.api.CodeModule, Listener {\n");
        add("private kr.codenamemc.codeengine.api.ModuleContext ctx;\n");
        for (Member member : module.members()) {
            if (member instanceof Field field) { add("private "); fragment(field.declaration()); add(";\n"); }
            if (member instanceof Function function) {
                add("private " + function.returnType() + " " + function.name() + "(" + function.parameters() + ") {\n");
                fragment(function.body()); add("\n}\n");
            }
        }
        add("@Override public void prepare(kr.codenamemc.codeengine.api.ModuleContext context) {\nthis.ctx = context;\n");
        int n = 0;
        for (Member member : module.members()) {
            if (member instanceof EventHandler event) {
                add("ctx.listen(" + event.type() + ".class, this, EventPriority." + event.priority() + ", " + event.ignoreCancelled() +
                    ", (__ceListener, __ceEvent) -> { if (__ceEvent instanceof " + event.type() + " " + event.variable() + ") __ceEvent" + n + "(" + event.variable() + "); });\n");
            } else if (member instanceof Command command) {
                add("ctx.command(\"" + command.name() + "\", \"" + command.permission() + "\", this::__ceCommand" + n + ");\n");
            } else if (member instanceof Timer timer) {
                add("ctx.every(" + timer.delay() + "L, " + timer.period() + "L, this::__ceTimer" + n + ");\n");
            }
            n++;
        }
        add("}\n"); n = 0;
        for (Member member : module.members()) {
            if (member instanceof EventHandler event) {
                add("private void __ceEvent" + n + "(" + event.type() + " " + event.variable() + ") {\n");
                fragment(event.body()); add("\n}\n");
            } else if (member instanceof Command command) {
                add("private boolean __ceCommand" + n + "(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {\n");
                fragment(command.body()); add("\n}\n");
            } else if (member instanceof Timer timer) {
                add("private void __ceTimer" + n + "() {\n"); fragment(timer.body()); add("\n}\n");
            } else if (member instanceof Lifecycle lifecycle) {
                add("@Override public void " + (lifecycle.enable() ? "enable" : "disable") + "() throws Exception {\n");
                fragment(lifecycle.body()); add("\n}\n");
            }
            n++;
        }
        add("}\n");
        return new GeneratedSource(packageName + ".Entry", out.toString(), lineMap);
    }
    private void fragment(Fragment fragment) {
        int original = fragment.line();
        for (String part : fragment.text().split("\n", -1)) { lineMap.put(line, original++); add(part + "\n"); }
    }
    private void add(String text) { out.append(text); line += (int) text.chars().filter(c -> c == '\n').count(); }
}
