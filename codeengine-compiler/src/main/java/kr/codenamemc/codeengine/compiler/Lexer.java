package kr.codenamemc.codeengine.compiler;

import java.util.ArrayList;
import java.util.List;

/** Structural lexer: strings and comments never contribute DSL delimiters. */
final class Lexer {
    static List<Token> scan(String source) {
        if (source.length() > 262144) throw new SourceException(1, "Source exceeds 256 Ki characters");
        // Java preprocesses Unicode escapes before tokenization. Reject this alternate syntax.
        if (source.contains("\\" + "u")) throw new SourceException(1, "Use literal Unicode, not Unicode escapes");
        var tokens = new ArrayList<Token>();
        int i = 0, line = 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { if (c == '\n') line++; i++; continue; }
            if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                continue;
            }
            if (source.startsWith("/*", i)) {
                int startLine = line;
                i += 2;
                while (i < source.length() && !source.startsWith("*/", i)) {
                    if (source.charAt(i++) == '\n') line++;
                }
                if (i == source.length()) throw new SourceException(startLine, "Unterminated comment");
                i += 2; continue;
            }
            int start = i, startLine = line;
            if (c == '"' || c == '\'') {
                boolean block = source.startsWith("\"\"\"", i);
                String delimiter = block ? "\"\"\"" : String.valueOf(c);
                i += delimiter.length();
                boolean closed = false;
                while (i < source.length()) {
                    if (source.startsWith(delimiter, i)) { i += delimiter.length(); closed = true; break; }
                    char current = source.charAt(i++);
                    if (current == '\n') { line++; if (!block) throw new SourceException(startLine, "Newline in literal"); }
                    if (current == '\\' && i < source.length()) { if (source.charAt(i++) == '\n') line++; }
                }
                if (!closed) throw new SourceException(startLine, "Unterminated literal");
            } else if (Character.isJavaIdentifierStart(c)) {
                while (++i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) { }
            } else if (Character.isDigit(c)) {
                while (++i < source.length() && Character.isDigit(source.charAt(i))) { }
            } else if (source.startsWith("->", i)) i += 2;
            else i++;
            tokens.add(new Token(source.substring(start, i), start, i, startLine));
        }
        tokens.add(new Token("<eof>", i, i, line));
        return List.copyOf(tokens);
    }
}
