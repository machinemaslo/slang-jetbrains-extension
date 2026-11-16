package slanglsp.editor;

import com.intellij.openapi.util.TextRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnusedRemovalTest {
    private static TextRange range(String text, String name, String kind) {
        return UnusedRemoval.range(text, new UnusedSymbolsAnnotator.Candidate(name, kind,
                TextRange.from(text.indexOf(name), name.length())));
    }

    @Test
    void removesWholeDeclarationsAndPreservesNeighbors() {
        String function = "// preceding comment\n[noinline]\nfloat unused() { if (true) { return 1; } return 0; }\nfloat kept;\n";
        var range = range(function, "unused", "function");
        assertNotNull(range);
        assertEquals("// preceding comment\nfloat kept;\n",
                function.substring(0, range.getStartOffset()) + function.substring(range.getEndOffset()));
        for (String declaration : new String[] {
                "struct Unused { float value; };", "enum Unused { A, B };", "float Unused = 1.0;",
                "static const float Unused = -2.0;"}) {
            String kind = declaration.startsWith("struct") ? "struct" : declaration.startsWith("enum") ? "enum" : "variable";
            String source = declaration + "\nfloat kept;\n";
            assertEquals(declaration + "\n", range(source, "Unused", kind).substring(source));
        }
        String locals = "void f() { float unused; float used = 1; return; }";
        assertEquals("float unused;", range(locals, "unused", "variable").substring(locals));
        String field = "struct S { float unused; float used; }";
        assertEquals("float unused;", range(field, "unused", "field").substring(field));
    }

    @Test
    void rejectsSideEffectsSharedDeclarationsAndControlHeaders() {
        for (String source : new String[] {
                "float unused = sideEffect();",
                "float unused = buffer[0];",
                "float unused = ++counter;",
                "float unused, used;",
                "float used, unused;",
                "for (float unused = 0; ; ) {}",
                "if (true) float unused;",
                "#define DECL float\nDECL unused;",
                "struct unused {} instance;",
                "struct unused {}instance;",
                "struct unused {}\ninstance;",
                "float unused = { 1, 2 };"
        }) {
            assertNull(range(source, "unused", source.startsWith("struct") ? "struct" : "variable"), source);
        }
    }
}
