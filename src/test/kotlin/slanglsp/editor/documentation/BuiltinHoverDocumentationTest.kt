package slanglsp.editor.documentation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slanglsp.editor.documentation.BuiltinHoverDocumentation.builtinModuleAtLocation
import slanglsp.editor.documentation.BuiltinHoverDocumentation.documentationAtLine

internal class BuiltinHoverDocumentationTest {
    @Test
    fun recognizesMissingAbsoluteBuiltinPathsButNotRealFiles(@TempDir directory: Path) {
        val parent = Files.createDirectory(directory.resolve("slang project (test)"))
        val core = parent.resolve("core")
        val glsl = parent.resolve("glsl")

        Assertions.assertEquals("core", builtinModuleAtLocation("core"))
        Assertions.assertEquals("core", builtinModuleAtLocation(core.toString()))
        Assertions.assertEquals("glsl", builtinModuleAtLocation(glsl.toString()))
        Assertions.assertNull(builtinModuleAtLocation(Path.of("src", "core").toString()))
        Assertions.assertNull(builtinModuleAtLocation(parent.resolve("core.slang").toString()))

        Files.createFile(core)
        Assertions.assertNull(builtinModuleAtLocation(core.toString()))
    }

    @Test
    fun extractsBlockDocumentationAcrossGenericAndIntrinsicModifiers() {
        val source =
            """
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
                
                """
                .trimIndent()
        val docs = documentationAtLine(source, 12)
        Assertions.assertTrue(docs.startsWith("A read-only buffer of `T` elements."))
        Assertions.assertTrue(docs.contains("**Parameter `T`:** The element type."))
        Assertions.assertTrue(docs.contains("**Remarks**"))
        Assertions.assertTrue(docs.contains("**See also:** `RWStructuredBuffer`."))
        Assertions.assertFalse(docs.contains("@category"))
        Assertions.assertFalse(docs.contains("__generic"))
    }

    @Test
    fun extractsLineDocumentationWithoutBorrowingFromAdjacentDeclarations() {
        val source =
            """
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
                
                """
                .trimIndent()
        Assertions.assertTrue(documentationAtLine(source, 6).contains("**Returns:** The sine of `x`."))
        Assertions.assertEquals("", documentationAtLine(source, 7))
        Assertions.assertEquals("", documentationAtLine(source, 10))
        Assertions.assertEquals("", documentationAtLine(source, 0))
        Assertions.assertEquals("", documentationAtLine(source, 100))
    }
}
