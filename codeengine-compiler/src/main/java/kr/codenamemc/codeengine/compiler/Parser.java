package kr.codenamemc.codeengine.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;
import static kr.codenamemc.codeengine.compiler.ModuleAst.*;

public final class Parser {
    private final String source;
    private final List<Token> tokens;
    private int index;
    public Parser(String source) { this.source = source; tokens = Lexer.scan(source); }
    public ModuleAst parse() {
        expect("module"); String id = take().text();
        if (!id.matches("[a-z][a-z0-9_]{0,47}")) fail("Invalid module id");
        expect(";");
        var imports = new ArrayList<String>();
        var members = new ArrayList<Member>();
        Set<String> unique = new HashSet<>();
        while (!at("<eof>")) {
            Token keyword = take();
            switch (keyword.text()) {
                case "use" -> {
                    String type = qualifiedName(); expect(";"); imports.add(type);
                }
                case "state" -> members.add(new Field(until(";")));
                case "fn" -> {
                    String name = identifier(); expect("(");
                    String params = until(")").text(); expect("->");
                    String result = until("{", false).text().strip();
                    members.add(new Function(name, params, result, body()));
                }
                case "on" -> {
                    String type = qualifiedName(), variable = identifier(), priority = "NORMAL";
                    boolean ignore = false;
                    if (consume("priority")) {
                        priority = take().text();
                        if (!Set.of("LOWEST", "LOW", "NORMAL", "HIGH", "HIGHEST", "MONITOR").contains(priority)) fail("Invalid event priority");
                    }
                    if (consume("ignoreCancelled")) ignore = true;
                    members.add(new EventHandler(type, variable, priority, ignore, body()));
                }
                case "command" -> {
                    String name = identifier();
                    if (!name.matches("[a-z][a-z0-9_]{0,47}") || name.equals("codeengine") || name.equals("ce")) fail("Invalid or reserved command name");
                    if (!unique.add("command:" + name)) fail("Duplicate command: " + name);
                    String permission = "";
                    if (consume("permission")) {
                        String literal = take().text();
                        if (!literal.matches("\"[a-z0-9_.-]+\"")) fail("Permission must be a lowercase literal");
                        permission = literal.substring(1, literal.length() - 1);
                    }
                    members.add(new Command(name, permission, body()));
                }
                case "every" -> {
                    long period = positiveNumber(); expect("ticks");
                    long delay = period;
                    if (consume("after")) { delay = positiveNumber(); expect("ticks"); }
                    members.add(new Timer(delay, period, body()));
                }
                case "enable", "disable" -> {
                    if (!unique.add(keyword.text())) fail("Duplicate lifecycle block");
                    members.add(new Lifecycle(keyword.text().equals("enable"), body()));
                }
                default -> throw new SourceException(keyword.line(), "Unknown declaration: " + keyword.text());
            }
        }
        return new ModuleAst(id, imports, members);
    }
    private long positiveNumber() {
        Token token = take();
        try { long value = Long.parseLong(token.text()); if (value > 0) return value; }
        catch (NumberFormatException ignored) { }
        throw new SourceException(token.line(), "Expected a positive tick count");
    }
    private String qualifiedName() {
        StringBuilder result = new StringBuilder(identifier());
        while (consume(".")) result.append('.').append(identifier());
        return result.toString();
    }
    private String identifier() {
        String value = take().text();
        if (!SourceVersion.isIdentifier(value) || SourceVersion.isKeyword(value) || value.startsWith("__ce")) fail("Invalid or reserved identifier: " + value);
        return value;
    }
    private Fragment body() { expect("{"); return until("}"); }
    private Fragment until(String end) { return until(end, true); }
    private Fragment until(String end, boolean consumeEnd) {
        Token first = peek(); int start = first.start();
        var stack = new java.util.ArrayDeque<String>();
        while (true) {
            Token token = peek();
            if (token.text().equals("<eof>")) fail("Expected '" + end + "'");
            if (stack.isEmpty() && at(end)) {
                String text = source.substring(start, token.start());
                if (consumeEnd) take();
                return new Fragment(text, first.line());
            }
            if (Set.of("{", "(", "[").contains(token.text())) {
                if (stack.size() >= 128) fail("Nesting exceeds 128 levels");
                stack.push(switch (token.text()) { case "{" -> "}"; case "(" -> ")"; default -> "]"; });
            } else if (Set.of("}", ")", "]").contains(token.text())) {
                if (stack.isEmpty() || !stack.pop().equals(token.text())) fail("Unmatched delimiter");
            }
            take();
        }
    }
    private boolean consume(String value) { if (!at(value)) return false; take(); return true; }
    private boolean at(String value) { return peek().text().equals(value); }
    private void expect(String value) { if (!consume(value)) fail("Expected '" + value + "'"); }
    private Token peek() { return tokens.get(index); }
    private Token take() { Token token = peek(); if (!at("<eof>")) index++; return token; }
    private void fail(String message) { throw new SourceException(peek().line(), message); }
}
