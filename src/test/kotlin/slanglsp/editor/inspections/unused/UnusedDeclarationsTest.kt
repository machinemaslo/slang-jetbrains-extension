package slanglsp.editor.inspections.unused

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import slanglsp.editor.inspections.unused.UnusedDeclarations.isExternal
import slanglsp.editor.inspections.unused.UnusedDeclarations.locals

internal class UnusedDeclarationsTest {
    @Test
    fun discoversLocalsWithoutTreatingExpressionsOrParametersAsDeclarations() {
        val code =
            """
                (float parameter) {
                    float unused;
                    const float initialized = call(parameter);
                    Thing object;
                    var inferred = 1;
                    let inferredToo = 2;
                    for (int index = 0; index < 2; index++) {
                        float nested;
                    }
                    return object;
                    call(parameter);
                    object.field = 1; // float comment;
                    string text = "float fake;";
                }
                
                """
                .trimIndent()

        val locals = locals(code, 0, code.length)
        Assertions.assertEquals(
            listOf("unused", "initialized", "object", "inferred", "inferredToo", "index", "nested", "text"),
            locals.map { it.name })

        for ((name, _, range) in locals) {
            Assertions.assertEquals(name, range.substring(code))
        }
    }

    @Test
    fun protectsEntryPointsAndExternalContracts() {
        Assertions.assertTrue(isExternal("main", "float "))
        Assertions.assertTrue(isExternal("compute", "[shader(\"compute\")]\n[numthreads(1,1,1)] void "))
        Assertions.assertFalse(isExternal("api", "public struct "))
        Assertions.assertTrue(isExternal("api", "export float "))
        Assertions.assertFalse(isExternal("helper", "private float "))
    }

    @Test
    fun ignoresFunctionPrototypes() {
        Assertions.assertTrue(locals("(float arg);", 0, 12).isEmpty())
    }
}
