import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.api.ModuleContext;
import kr.codenamemc.codeengine.compiler.ModuleCompiler;
import kr.codenamemc.codeengine.compiler.Parser;
import kr.codenamemc.codeengine.runtime.RuntimeClasspath;
import kr.codenamemc.codeengine.runtime.dependency.DependencyClasspath;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Opt-in real-JAR check. No Paper server, plugin startup, downloads, or production state. */
class VerifyBetterModelApi {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected: BetterModel Paper JAR, library directory, build directory");
        Path pluginJar = Path.of(args[0]).toRealPath();
        Path libraryDirectory = Path.of(args[1]).toRealPath();
        Path builds = Path.of(args[2]);
        ClassLoader parent = CodeModule.class.getClassLoader();
        String base = RuntimeClasspath.collect(CodeModule.class, org.bukkit.Bukkit.class, Component.class);
        List<URL> urls = new ArrayList<>();
        try (var paths = Files.list(libraryDirectory)) {
            for (Path jar : paths.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) urls.add(jar.toUri().toURL());
        }
        try (var libraries = new URLClassLoader(urls.toArray(URL[]::new), parent);
             var provider = new URLClassLoader(new URL[]{pluginJar.toUri().toURL()}, libraries)) {
            var ast = new Parser("""
                module bettermodel_test;
                use kr.toxicity.model.api.BetterModel from "BetterModel";
                command modelcheck {
                    if (BetterModel.model("demon_knight").isPresent()) {
                        sender.sendMessage(Component.text("모델을 찾았습니다!"));
                    } else {
                        sender.sendMessage(Component.text("모델이 없습니다."));
                    }
                    return true;
                }
                """).parse();
            var resolved = DependencyClasspath.prepare(base,
                List.of(new DependencyClasspath.Provider("BetterModel", pluginJar, provider)), ast.imports(), parent);
            Path semverSource = resolved.selectedClasses().get("org.semver4j.Semver");
            require(semverSource != null && !semverSource.equals(pluginJar), "Semver must come from a separate selected library JAR");
            var compiler = new ModuleCompiler(builds, base);
            var compiled = compiler.compile(ast, ast.id(), resolved.selectedClasses());
            Class<?> api = provider.loadClass("kr.toxicity.model.api.BetterModel");
            Class<?> platformType = provider.loadClass("kr.toxicity.model.api.BetterModelPlatform");
            Class<?> managerType = provider.loadClass("kr.toxicity.model.api.manager.ModelManager");
            Class<?> semverType = libraries.loadClass("org.semver4j.Semver");
            Object semver = semverType.getConstructor(String.class).newInstance("3.5.0");
            AtomicInteger modelCalls = new AtomicInteger();
            Object manager = Proxy.newProxyInstance(provider, new Class<?>[]{managerType}, (proxy, method, arguments) -> {
                if (method.getName().equals("model")) {
                    require(arguments[0].equals("demon_knight"), "The command must pass the original model name");
                    modelCalls.incrementAndGet();
                    return null;
                }
                throw new UnsupportedOperationException(method.toString());
            });
            Object platform = Proxy.newProxyInstance(provider, new Class<?>[]{platformType}, (proxy, method, arguments) -> {
                if (method.getName().equals("manager")) {
                    require(arguments[0] == managerType, "The real API must request its original ModelManager type");
                    return manager;
                }
                if (method.getName().equals("semver")) return semver;
                throw new UnsupportedOperationException(method.toString());
            });
            api.getMethod("register", platformType).invoke(null, platform);
            List<CommandExecutor> commands = new ArrayList<>();
            ModuleContext context = (ModuleContext) Proxy.newProxyInstance(parent, new Class<?>[]{ModuleContext.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("command")) {
                    require(arguments[0].equals("modelcheck"), "The original command must register");
                    commands.add((CommandExecutor) arguments[2]);
                    return null;
                }
                throw new UnsupportedOperationException(method.toString());
            });
            List<Component> messages = new ArrayList<>();
            CommandSender sender = (CommandSender) Proxy.newProxyInstance(parent, new Class<?>[]{CommandSender.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("sendMessage") && arguments.length == 1 && arguments[0] instanceof Component message) {
                    messages.add(message);
                    return null;
                }
                throw new UnsupportedOperationException(method.toString());
            });
            try (var module = resolved.newLoader(compiled.jar(), parent)) {
                require(module.loadClass(api.getName()) == api, "The module must reuse the original BetterModel Class");
                require(module.loadClass(semverType.getName()) == semverType, "The module must reuse the original Semver Class");
                CodeModule instance = module.loadClass(compiled.className()).asSubclass(CodeModule.class).getConstructor().newInstance();
                instance.prepare(context);
                instance.enable();
                require(commands.size() == 1, "Exactly one command must register");
                require(commands.getFirst().onCommand(sender, null, "modelcheck", new String[0]), "Command result must be true");
                require(modelCalls.get() == 1, "The command must invoke the real BetterModel.model exactly once");
                require(messages.equals(List.of(Component.text("모델이 없습니다."))), "Missing-model message must match the original source");
            }
            var libraryAst = new Parser("""
                module bettermodel_library;
                use kr.toxicity.model.api.BetterModel from "BetterModel";
                enable {
                    if (BetterModel.platform().semver().getMajor() != 3) throw new AssertionError("wrong library definition");
                }
                """).parse();
            var libraryModule = compiler.compile(libraryAst, libraryAst.id(), resolved.selectedClasses());
            try (var module = resolved.newLoader(libraryModule.jar(), parent)) {
                CodeModule instance = module.loadClass(libraryModule.className()).asSubclass(CodeModule.class).getConstructor().newInstance();
                instance.prepare(null);
                instance.enable();
            }
            System.out.println("PASS: selected " + resolved.selectedClasses().size() + " API/signature types");
            System.out.println("PASS: Semver source is " + semverSource.getFileName() + ", original Class identity retained");
            System.out.println("PASS: original modelcheck source compiles and invokes real BetterModel.model; missing-model message matches");
            System.out.println("PASS: native BetterModel.platform().semver().getMajor() library call");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
