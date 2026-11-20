package slanglsp.editor.navigation.cpp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ReflectionSourceTest {
    @Test
    fun followsLayoutBindingsToAQualifiedCppTypeWithADifferentName() {
        val source = """
            #include <slang/slang.h>
            bool validate(slang::ProgramLayout* program) {
                auto type = program->findTypeByName("Material");
                auto layout = program->getTypeLayout(type);
                auto size = layout->getSize();
                if (!layout) { return false; }
                return size == sizeof(::render::MaterialData);
            }
        """.trimIndent()

        val evidence = ReflectionSource.inspect(source, "Material")

        assertEquals(
            listOf(ReflectionSource.TypeReference("::render::MaterialData", source.indexOf("MaterialData"))),
            evidence.types,
        )
        assertTrue(evidence.site!!.namesType)
        assertTrue(ReflectionSource.inspect(source, "Other").types.isEmpty())
    }

    @Test
    fun recognizesInlineAlignmentAndFieldOffsetChecks() {
        val source = """
            #include "my-slang-wrapper.h"
            void check(slang::ProgramLayout* program) {
                assert(alignof(CpuMaterial) ==
                       program->getTypeLayout(program->findTypeByName("Material"))->getAlignment());
                auto layout = program->getTypeLayout(program->findTypeByName("Material"));
                assert(layout->getFieldByIndex(0)->getOffset() == offsetof(CpuMaterial, color));
            }
        """.trimIndent()

        assertEquals(
            listOf(
                ReflectionSource.TypeReference("CpuMaterial", source.indexOf("CpuMaterial"), typeOnly = true),
                ReflectionSource.TypeReference("CpuMaterial", source.lastIndexOf("CpuMaterial"), typeOnly = true),
            ),
            ReflectionSource.inspect(source, "Material").types,
        )
    }

    @Test
    fun recognizesLegacySlangReflectionFunctions() {
        val source = """
            int check(SlangReflection* program) {
                auto type = spReflection_FindTypeByName(program, "Material");
                auto layout = spReflection_GetTypeLayout(program, type, rules);
                return spReflectionTypeLayout_GetSize(layout, category) != sizeof(HostMaterial);
            }
        """.trimIndent()

        assertEquals(listOf("HostMaterial"), ReflectionSource.inspect(source, "Material").types.map { it.name })
    }

    @Test
    fun doesNotTreatNearbyTypesCommentsOrStringsAsMappings() {
        val source = """
            #include <slang.h>
            void check() {
                // auto type = program->findTypeByName("Material");
                auto type = program->findTypeByName("Other");
                const auto message = u8R"code(program->findTypeByName("Material"))code";
                auto layout = program->getTypeLayout(type);
                assert(layout->getSize() == sizeof(HostOther));
                auto material = program->findTypeByName("Material");
                auto unrelated = sizeof(HostMaterial);
            }
        """.trimIndent()

        val evidence = ReflectionSource.inspect(source, "Material")

        assertTrue(evidence.types.isEmpty())
        assertEquals(ReflectionSource.Site(source.lastIndexOf("findTypeByName"), true), evidence.site)
        assertNull(ReflectionSource.inspect("/*#include <slang.h>*/ auto x = y.findTypeByName(\"Material\");", "Material").site)
    }

    @Test
    fun doesNotKeepAnAliasAfterItIsReassigned() {
        val source = """
            #include <slang.h>
            void check() {
                auto type = program->findTypeByName("Material");
                if (flag) {
                    type = program->findTypeByName("Other");
                }
                auto layout = program->getTypeLayout(type);
                assert(layout->getSize() == sizeof(Other));
            }
        """.trimIndent()

        assertTrue(ReflectionSource.inspect(source, "Material").types.isEmpty())
    }

    @Test
    fun locatesGenericReflectionWithoutInventingACppType() {
        val source = """
               #include "slang.h"
            auto type = program->findTypeByName(name);
            auto layout = program->getTypeLayout(type);
        """.trimIndent()

        val evidence = ReflectionSource.inspect(source, "Material")

        assertTrue(evidence.types.isEmpty())
        assertFalse(evidence.site!!.namesType)
    }

    @Test
    fun recognizesLocalTypeNameConstantsAndElaboratedHostTypes() {
        val source = """
            #include "slang.h"
            void check(slang::ProgramLayout* program) {
                constexpr const char* name = "Material";
                auto type = program->findTypeByName(name);
                return program->getTypeLayout(type)->getSize() == sizeof(struct HostMaterial);
            }
        """.trimIndent()

        assertEquals(
            listOf(ReflectionSource.TypeReference("HostMaterial", source.indexOf("HostMaterial"), typeOnly = true)),
            ReflectionSource.inspect(source, "Material").types,
        )
    }
}
