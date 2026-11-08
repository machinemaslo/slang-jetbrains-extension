package slanglsp.editor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnusedDeclarationsTest {
    @Test
    void discoversLocalsWithoutTreatingExpressionsOrParametersAsDeclarations() {
        String code = "(float parameter) { float unused; const float initialized = call(parameter); "
                      + "Thing object; var inferred = 1; let inferredToo = 2; "
                      + "for (int index = 0; index < 2; index++) { float nested; } "
                      + "return object; call(parameter); object.field = 1; // float comment;\n"
                      + "string text = \"float fake;\"; }";

        var locals = UnusedDeclarations.locals(code, 0, code.length());
        assertEquals(List.of("unused", "initialized", "object", "inferred", "inferredToo", "index", "nested", "text"),
                locals.stream().map(UnusedSymbolsAnnotator.Candidate::name).toList());

        for (var local : locals) {
            assertEquals(local.name(), local.range().substring(code));
        }
    }

    @Test
    void protectsEntryPointsAndExternalContracts() {
        assertTrue(UnusedDeclarations.isExternal("main", "float "));
        assertTrue(UnusedDeclarations.isExternal("compute", "[shader(\"compute\")]\n[numthreads(1,1,1)] void "));
        assertFalse(UnusedDeclarations.isExternal("api", "public struct "));
        assertTrue(UnusedDeclarations.isExternal("api", "export float "));
        assertFalse(UnusedDeclarations.isExternal("helper", "private float "));
    }

    @Test
    void ignoresFunctionPrototypes() {
        assertTrue(UnusedDeclarations.locals("(float arg);", 0, 12).isEmpty());
    }
}
