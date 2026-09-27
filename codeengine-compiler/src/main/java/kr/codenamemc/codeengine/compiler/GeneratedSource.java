package kr.codenamemc.codeengine.compiler;
import java.util.Map;
public record GeneratedSource(String className, String source, Map<Integer, Integer> lineMap) {
    public GeneratedSource { lineMap = Map.copyOf(lineMap); }
    public int sourceLine(long generatedLine) { return lineMap.getOrDefault((int) generatedLine, 1); }
}
