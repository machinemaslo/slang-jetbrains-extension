package slanglsp.editor.navigation.cpp

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ReflectionProbeMatcherTest {
    @Test
    fun modifiesOnlyTheSelectedDeclarationEvenWhenTwoStructsShareAName() {
        val source = """
            namespace first { public struct Params { public float value; }; }
            namespace other {
                public struct Params { /* } */ public float other; float copy() { return other; } };
            }
        """.trimIndent()
        val selected = source.lastIndexOf("Params")

        val modified = ReflectionProbeMatcher.inject(source, selected, ReflectionProbeMatcher.Shape.ARRAY, 44)!!

        assertTrue(modified.startsWith(source.substring(0, selected)))
        assertEquals(1, Regex("""public uint slangReflectionProbe\d+\[44];""").findAll(modified).count())
        assertTrue(modified.indexOf("slangReflectionProbe") > modified.indexOf("return other;"))
    }

    @Test
    fun findsADeclarationWithoutDependingOnTypeNamesFieldNamesOrOutputPaths() {
        val live = Path.of("generated", "reflection.data")
        val original = """
            struct alignas(16) T42 {
                glm::vec3 A = {};
                float B = {};
            };
            struct Unrelated { int c; };
        """.trimIndent()
        val first = "struct OtherName { glm::vec3 A = {}; float B = {}; std::array<unsigned, 44u> x = {}; };"
        val second = "struct D { glm::vec3 A = {}; float B = {}; unsigned renamed[68] = {}; };"

        val match = ReflectionProbeMatcher.match(
            mapOf(live to original),
            mapOf(Path.of("first.inc") to first),
            mapOf(Path.of("different", "second.hpp") to second),
        )

        assertEquals(ReflectionProbeMatcher.Candidate(live, original.indexOf("T42"), "T42"), match)
    }

    @Test
    fun rejectsUnchangedTargetsAndTiedCandidates() {
        val original = "struct A { float value; };"
        val first = "struct A { float value; unsigned x[44]; };"
        val second = "struct B { float value; unsigned y[68]; };"
        val baseline = mapOf(Path.of("output.inc") to original)
        val firstArtifacts = mapOf(Path.of("first.inc") to first)

        assertNull(ReflectionProbeMatcher.match(baseline, firstArtifacts, mapOf(Path.of("second.inc") to original + second)))
        assertNull(ReflectionProbeMatcher.match(
            baseline + (Path.of("ambiguous.inc") to "struct C { float value; };"),
            firstArtifacts,
            mapOf(Path.of("second.inc") to second),
        ))
        assertNull(ReflectionProbeMatcher.match(baseline, firstArtifacts, mapOf(Path.of("second.inc") to first)))
    }

    @Test
    fun recognizesTheScalarFallbackWithoutMatchingFieldNames() {
        val path = Path.of("generated.inc")
        val original = "struct T42 { float old; };"
        fun probe(size: Int): String =
            "struct Probe { float old; " + (0 until size).joinToString("") { "float f$it = {}; " } + "};"

        val match = ReflectionProbeMatcher.match(
            mapOf(path to original),
            mapOf(path to probe(8)),
            mapOf(path to probe(12)),
            ReflectionProbeMatcher.Shape.SCALARS,
        )

        assertEquals(ReflectionProbeMatcher.Candidate(path, original.indexOf("T42"), "T42"), match)
    }
}
