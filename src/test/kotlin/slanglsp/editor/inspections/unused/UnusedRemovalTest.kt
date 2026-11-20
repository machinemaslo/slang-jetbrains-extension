package slanglsp.editor.inspections.unused

import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import slanglsp.editor.inspections.unused.UnusedRemoval.range

internal class UnusedRemovalTest {
    @Test
    fun removesWholeDeclarationsAndPreservesNeighbors() {
        val function =
            "// preceding comment\n[noinline]\nfloat unused() { if (true) { return 1; } return 0; }\nfloat kept;\n"
        val range: TextRange? = range(function, "unused", "function")
        Assertions.assertNotNull(range)
        Assertions.assertEquals(
            "// preceding comment\nfloat kept;\n",
            function.substring(0, range!!.startOffset) + function.substring(range.endOffset))
        for (declaration in
            arrayOf(
                "struct Unused { float value; };",
                "enum Unused { A, B };",
                "float Unused = 1.0;",
                "static const float Unused = -2.0;",
                "static const String Unused = \"Hello\\nmispellled shader\";")) {
            val kind =
                if (declaration.startsWith("struct")) "struct"
                else if (declaration.startsWith("enum")) "enum" else "variable"
            val source = "$declaration\nfloat kept;\n"
            Assertions.assertEquals(declaration + "\n", range(source, "Unused", kind)!!.substring(source))
        }
        val locals = "void f() { float unused; float used = 1; return; }"
        Assertions.assertEquals("float unused;", range(locals, "unused", "variable")!!.substring(locals))
        val field = "struct S { float unused; float used; }"
        Assertions.assertEquals("float unused;", range(field, "unused", "field")!!.substring(field))
    }

    @Test
    fun rejectsSideEffectsSharedDeclarationsAndControlHeaders() {
        for (source in
            arrayOf(
                "float unused = sideEffect();",
                "String unused = \"hello\" + sideEffect();",
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
                "float unused = { 1, 2 };")) {
            Assertions.assertNull(
                range(source, "unused", if (source.startsWith("struct")) "struct" else "variable"), source)
        }
    }

    companion object {
        private fun range(text: String, name: String, kind: String): TextRange? {
            return range(
                text, UnusedSymbolsAnnotator.Candidate(name, kind, TextRange.from(text.indexOf(name), name.length)))
        }
    }
}
