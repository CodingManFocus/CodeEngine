package kr.codenamemc.codeengine.runtime;
import java.net.URLClassLoader;
import java.util.Objects;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.compiler.CompiledModule;

/** One main-thread owned lifetime, including construction before the module instance exists. */
final class LoadedModule {
    private CodeModule module;
    private final ModuleScope scope;
    private final URLClassLoader loader;
    private final CompiledModule compiled;

    LoadedModule(CodeModule module, ModuleScope scope, URLClassLoader loader, CompiledModule compiled) {
        this.module = module;
        this.scope = Objects.requireNonNull(scope);
        this.loader = Objects.requireNonNull(loader);
        this.compiled = Objects.requireNonNull(compiled);
    }

    void initialize(CodeModule instance) {
        if (module != null) throw new IllegalStateException("Module instance is already initialized");
        module = Objects.requireNonNull(instance);
    }

    CodeModule module() { return module; }
    ModuleScope scope() { return scope; }
    URLClassLoader loader() { return loader; }
    CompiledModule compiled() { return compiled; }
}
