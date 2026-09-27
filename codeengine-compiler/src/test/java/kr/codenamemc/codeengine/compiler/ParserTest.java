package kr.codenamemc.codeengine.compiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ParserTest {
    @Test void parsesAllDeclarations() {
        var ast = new Parser("""
            module sample;
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
