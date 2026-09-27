package kr.codenamemc.codeengine.runtime;
import java.net.URLClassLoader;
import kr.codenamemc.codeengine.api.CodeModule;
import kr.codenamemc.codeengine.compiler.CompiledModule;
record LoadedModule(CodeModule module, ModuleScope scope, URLClassLoader loader, CompiledModule compiled) { }
