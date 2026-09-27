package kr.codenamemc.codeengine.compiler;

import java.util.List;

public record ModuleAst(String id, List<String> imports, List<String> pluginDependencies, List<Member> members) {
    public ModuleAst {
        imports = List.copyOf(imports);
        pluginDependencies = List.copyOf(pluginDependencies);
        members = List.copyOf(members);
    }
    public record Fragment(String text, int line) { }
    public sealed interface Member permits Field, Function, EventHandler, Command, Timer, Lifecycle { }
    public record Field(Fragment declaration) implements Member { }
    public record Function(String name, String parameters, String returnType, Fragment body) implements Member { }
    public record EventHandler(String type, String variable, String priority, boolean ignoreCancelled, Fragment body) implements Member { }
    public record Command(String name, String permission, Fragment body) implements Member { }
    public record Timer(long delay, long period, Fragment body) implements Member { }
    public record Lifecycle(boolean enable, Fragment body) implements Member { }
}
