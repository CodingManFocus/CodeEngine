package kr.codenamemc.codeengine.compiler;
import java.nio.file.Path;
public record CompiledModule(String id, String className, Path jar, Path generatedSource) { }
