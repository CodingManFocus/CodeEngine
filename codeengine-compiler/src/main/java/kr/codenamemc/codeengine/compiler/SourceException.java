package kr.codenamemc.codeengine.compiler;

public final class SourceException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final int line;
    public SourceException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }
    public int line() { return line; }
}
