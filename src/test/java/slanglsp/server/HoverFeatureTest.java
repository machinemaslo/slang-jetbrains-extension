package slanglsp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HoverFeatureTest {
    @Test
    void extractsBlockDocumentationAcrossGenericAndIntrinsicModifiers() {
        String source = """
                /**
                 * A read-only buffer of `T` elements.
                 * @param T The element type.
                 * @remarks
                 * Uses the selected layout.
                 * @see `RWStructuredBuffer`.
                 * @category buffer_types Buffer types
                 **/
                __generic<T, L:IBufferDataLayout=DefaultDataLayout>
                __magic_type(HLSLStructuredBufferType)
                __intrinsic_type(116)
                struct StructuredBuffer : IArray<T>
                {
                };
                """;
        String docs = HoverFeature.documentationAtLine(source, 12);
        assertTrue(docs.startsWith("A read-only buffer of `T` elements."));
        assertTrue(docs.contains("**Parameter `T`:** The element type."));
        assertTrue(docs.contains("**Remarks**"));
        assertTrue(docs.contains("**See also:** `RWStructuredBuffer`."));
        assertFalse(docs.contains("@category"));
        assertFalse(docs.contains("__generic"));
    }

    @Test
    void extractsLineDocumentationWithoutBorrowingFromAdjacentDeclarations() {
        String source = """
                /// Sine.
                /// @param x Angle in radians.
                /// @return The sine of `x`.
                __generic<T>
                [require(cpp_cuda, sm_4_0_version)]
                T sin(T x);
                T undocumented(T x);
                /// A container.
                struct Container {
                    int undocumentedField;
                }
                """;
        assertTrue(HoverFeature.documentationAtLine(source, 6).contains("**Returns:** The sine of `x`."));
        assertEquals("", HoverFeature.documentationAtLine(source, 7));
        assertEquals("", HoverFeature.documentationAtLine(source, 10));
        assertEquals("", HoverFeature.documentationAtLine(source, 0));
        assertEquals("", HoverFeature.documentationAtLine(source, 100));
    }
}
