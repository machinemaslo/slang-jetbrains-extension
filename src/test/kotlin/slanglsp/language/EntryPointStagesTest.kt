package slanglsp.language

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class EntryPointStagesTest {
    @Test
    fun collectsDistinctStagesInSourceOrder() {
        val source =
            """
            [shader(/* stage */ "compute")]
            [numthreads(8, 8, 1)]
            void main(uint3 id : SV_DispatchThreadID) {}

            [numthreads(1, 1, 1)]
            [shader("compute")]
            public void clear() {}

            [shader("vertex")]
            float4 draw(float3 position : POSITION) : SV_Position { return float4(position, 1); }

            [shader("pixel")]
            float4 light() : SV_Target { return 1; }

            [shader("fragment")]
            float4 overlay() : SV_Target { return 0; }
            """.trimIndent()

        assertEquals(listOf("compute", "vertex", "fragment"), EntryPointStages.collect(source))
    }

    @Test
    fun ignoresPlainMainCommentsStringsAndNonFunctionAttributes() {
        val source =
            """
            // [shader("compute")] void notAnEntryPoint() {}
            /* [shader("vertex")] float4 notAnEntryPoint() { return 0; } */
            String text = "[shader(\"fragment\")] void notAnEntryPoint() {}";
            [shader("node")] int value = helper();
            [shader("mesh")] struct NotAFunction {};
            [shader("not_a_stage")] void unknown() {}
            [shader("closesthit", 1)] void invalidAttribute() {}
            [numthreads(1, 1, 1)] void main() {}
            """.trimIndent()

        assertEquals(emptyList<String>(), EntryPointStages.collect(source))
    }
}
