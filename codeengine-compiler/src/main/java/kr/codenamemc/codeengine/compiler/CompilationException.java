package kr.codenamemc.codeengine.compiler;
import java.util.List;
public final class CompilationException extends Exception {
    private static final long serialVersionUID = 1L;
    private final List<String> diagnostics;
    public CompilationException(List<String> diagnostics) {
        super(String.join("\n", diagnostics)); this.diagnostics = List.copyOf(diagnostics);
    }
    public List<String> diagnostics() { return diagnostics; }
}
