package kr.codenamemc.codeengine.compiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ParserTest {
    @Test void parsesAllDeclarations() {
        var ast = new Parser("""
            module sample;
            requires plugin "LuckPerms";
            use org.bukkit.event.entity.EntityDamageEvent;
            state long count = 0L;
            fn twice(int n) -> int { return n * 2; }
            on EntityDamageEvent event priority HIGH ignoreCancelled { event.setCancelled(true); }
            command sample permission "sample.use" { return true; }
            every 20 ticks after 1 ticks { count++; }
            enable { }
            disable { }
            """).parse();
        assertEquals("sample", ast.id()); assertEquals(7, ast.members().size()); assertEquals(1, ast.imports().size());
        assertEquals(List.of("LuckPerms"), ast.pluginDependencies());
    }
    @Test void preservesDependencyOrderAndCaseAlongsideImports() {
        var ast = new Parser("""
            module sample;
            requires plugin "LuckPerms";
            use java.time.Instant;
            requires plugin "Example_Plugin-2.0";
            """).parse();
        assertEquals(List.of("LuckPerms", "Example_Plugin-2.0"), ast.pluginDependencies());
        assertEquals(List.of("java.time.Instant"), ast.imports().stream().map(ModuleAst.Import::type).toList());
        assertTrue(ast.members().isEmpty());
    }
    @Test void qualifiedImportsInferDependenciesAndPreserveSourceLines() {
        var ast = new Parser("""
            module sample;
            use example.Api from "First";
            use example.Other from "First";
            use another.Api from "Second";
            use java.time.Instant;
            """).parse();
        assertEquals(List.of("First", "Second"), ast.pluginDependencies());
        assertEquals(new ModuleAst.Import("example.Api", "First", 2), ast.imports().getFirst());
        String java = new JavaEmitter().emit(ast).source();
        assertTrue(java.contains("import example.Api;"));
        assertFalse(java.contains("from "));
    }
    @Test void explicitLifecycleDependencyAndImplicitImportDependencyAreDeduplicated() {
        var ast = new Parser("module sample; use example.Api from \"First\"; requires plugin \"First\";").parse();
        assertEquals(List.of("First"), ast.pluginDependencies());
    }
    @ParameterizedTest @ValueSource(strings = {
        "use example.Api from First;", "use example.Api from \"../First\";",
        "use example.Api from \"First\"; use example.Api from \"Second\";",
        "use example.Api; use example.Api from \"First\";",
        "use example.Api from \"First\"; use example.Other from \"first\";"
    }) void rejectsInvalidOrConflictingImportProvider(String source) {
        assertThrows(SourceException.class, () -> new Parser("module sample; " + source).parse());
    }
    @Test void moduleWithoutDependenciesKeepsEmptyDependencyList() {
        assertTrue(new Parser("module sample;").parse().pluginDependencies().isEmpty());
    }
    @Test void dependencyListIsAnImmutableSnapshot() {
        var dependencies = new ArrayList<>(List.of("LuckPerms"));
        var ast = new ModuleAst("sample", List.of(), dependencies, List.of());
        dependencies.add("OtherPlugin");
        assertEquals(List.of("LuckPerms"), ast.pluginDependencies());
        assertThrows(UnsupportedOperationException.class, () -> ast.pluginDependencies().add("OtherPlugin"));
    }
    @Test void dependencyDeclarationsGenerateNoRuntimeDispatch() {
        String withDependency = new JavaEmitter().emit(new Parser("module sample; requires plugin \"LuckPerms\";").parse()).source();
        String withoutDependency = new JavaEmitter().emit(new Parser("module sample;").parse()).source();
        assertEquals(withoutDependency, withDependency);
    }
    @ParameterizedTest @ValueSource(strings = {
        "requires plugin LuckPerms;", "requires plugin \"\";", "requires plugin \"../LuckPerms\";",
        "requires plugin \"Luck Perms\";", "requires plugin \"Luck:Perms\";", "requires module \"LuckPerms\";",
        "requires plugin \"LuckPerms\"", "requires plugin \"LuckPerms\"; requires plugin \"luckperms\";",
        "requires plugin \"LuckPerms\"; requires plugin \"LuckPerms\";", "requires plugin \"Luck\\\\Perms\";"
    }) void rejectsInvalidPluginDependency(String declaration) {
        assertThrows(SourceException.class, () -> new Parser("module sample; " + declaration).parse());
    }
    @Test void malformedDependencyReportsLiteralLine() {
        var error = assertThrows(SourceException.class,
            () -> new Parser("module sample;\nrequires plugin\n\"bad/name\";\n").parse());
        assertEquals(3, error.line());
    }
    @Test void duplicateDependencyReportsDeclarationLine() {
        var error = assertThrows(SourceException.class,
            () -> new Parser("module sample;\nrequires plugin \"LuckPerms\";\n\nrequires plugin \"luckperms\";\n").parse());
        assertEquals(4, error.line());
    }
    @Test void delimitersInsideLiteralsAndCommentsAreNotSyntax() {
        var ast = new Parser("module test; command hello { String s = \"} /* { \\\"\"; char c = '}'; /* } */ // }\n return true; }").parse();
        assertEquals(1, ast.members().size());
    }
    @Test void supportsJavaTextBlocks() {
        var ast = new Parser("module test; state String value = \"\"\"\n } { //\n\"\"\";").parse();
        assertEquals(1, ast.members().size());
    }
    @Test void generatesDirectPaperRegistration() {
        String generated = new JavaEmitter().emit(new Parser("module test; on BlockBreakEvent e { e.setCancelled(true); }").parse()).source();
        assertTrue(generated.contains("ctx.listen(BlockBreakEvent.class"));
        assertTrue(generated.contains("e.setCancelled(true)"));
        assertFalse(generated.contains("reflect"));
    }
    @ParameterizedTest @ValueSource(strings = {
        "module ../bad;", "module Test;", "module test; unknown {}", "module test; enable {", "module test; state String x = \"oops;",
        "module test; /* never", "module test; every 0 ticks {}", "module test; every 9223372036854775808 ticks {}",
        "module test; enable {} enable {}", "module test; command hi {return true;} command hi {return true;}",
        "module test; command ce {return true;}", "module test; on BlockBreakEvent e priority FAST {}",
        "module test; fn __ceInternal() -> void {}", "module test; state int n = (1];"
    }) void rejectsInvalidSyntax(String source) { assertThrows(SourceException.class, () -> new Parser(source).parse()); }
    @Test void rejectsJavaUnicodePreprocessing() {
        assertThrows(SourceException.class, () -> new Parser("module test; // " + "\\" + "u000a evil").parse());
    }
    @Test void diagnosesOriginalLine() {
        var error = assertThrows(SourceException.class, () -> new Parser("module test;\n\nunknown;").parse());
        assertEquals(3, error.line());
    }
}
