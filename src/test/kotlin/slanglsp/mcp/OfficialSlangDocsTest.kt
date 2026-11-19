package slanglsp.mcp

import java.io.IOException
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import slanglsp.mcp.LinkedDocs.releaseTag
import slanglsp.mcp.LinkedDocs.sources

internal class OfficialSlangDocsTest {
    @Test
    fun findsRelevantCitedExcerptsWithoutReturningTheWholeReference() {
        val content =
            OfficialSlangDocs.parse(
                """
                # Slang Language Documentation

                ## Automatic Differentiation
                Unrelated section.

                ## Generics
                ### Generic Value Parameters
                A parameter declared with `let` takes a value known at specialization time.

                ```slang
                #define COUNT 4
                void run<let N : int>() {}
                ```
                
                """
                    .trimIndent(),
                """
                - [Generics](https://docs.shader-slang.org/generics): Generic value parameters and constraints.
                
                """
                    .trimIndent())
        val result: String = OfficialSlangDocs.search(content, "generic value parameters")
        Assertions.assertTrue(result.contains(OfficialSlangDocs.REFERENCE), result)
        Assertions.assertTrue(result.contains("https://docs.shader-slang.org/generics"), result)
        Assertions.assertTrue(result.contains("run<let N : int>"), result)
        Assertions.assertFalse(result.contains("Unrelated section"), result)
    }

    @Test
    fun capsLongMatchesAndPointsUnmatchedQueriesAtTheOfficialIndex() {
        val content = OfficialSlangDocs.parse("## Generics\n" + "generic ".repeat(500), "")
        Assertions.assertTrue(OfficialSlangDocs.search(content, "generics").length < 1000)
        Assertions.assertTrue(OfficialSlangDocs.search(content, "no-such-intrinsic").contains(OfficialSlangDocs.INDEX))
    }

    @Test
    @Throws(Exception::class)
    fun followsRankedGuideAndPrefersTheRequestedRelease() {
        val corpus =
            OfficialSlangDocs.parse(
                "## Generics\nAn upstream overview.",
                """
                - [Interfaces and Generics](https://shader-slang.com/slang/user-guide/interfaces-generics): Generic constraints.
                - [Unrelated](https://github.com/shader-slang/slang/tree/master/examples/triangle): Triangle.
                
                """
                    .trimIndent())
        val requests = ArrayList<String?>()
        val result: String =
            OfficialSlangDocs.search(corpus, "generic constraints", releaseTag("2026.22")) { url: String? ->
                requests.add(url)
                """
                    ---
                    layout: user-guide
                    ---
                    A generic constraint limits the types you can pass.
                    
                    """
                    .trimIndent()
            }
        Assertions.assertEquals(
            mutableListOf<String?>(
                "https://raw.githubusercontent.com/shader-slang/slang/v2026.22/docs/user-guide/06-interfaces-generics.md"),
            requests)
        Assertions.assertTrue(result.contains("[SDK v2026.22]"), result)
        Assertions.assertTrue(result.contains("limits the types"), result)
        Assertions.assertFalse(result.contains("layout: user-guide"), result)
    }

    @Test
    @Throws(Exception::class)
    fun fallsBackToUpstreamThenPreservesTheIndexWhenNeitherPageIsAvailable() {
        val corpus =
            OfficialSlangDocs.parse(
                "",
                """
                - [Hello World Compute](https://github.com/shader-slang/slang/tree/master/examples/hello-world): Minimal compute example.
                
                """
                    .trimIndent())
        val result: String =
            OfficialSlangDocs.search(corpus, "hello world", "v2026.22") { url ->
                if (url.contains("/v2026.22/")) {
                    throw IOException("not found")
                }
                "A runnable Slang example."
            }
        Assertions.assertTrue(
            result.contains("[upstream]") && result.contains("/master/examples/hello-world/README.md"), result)
        val offline: String =
            OfficialSlangDocs.search(corpus, "hello world", "v2026.22") {
                throw IOException("unavailable")
            }
        Assertions.assertTrue(offline.contains("Minimal compute example."), offline)
    }

    @Test
    @Throws(Exception::class)
    fun retrievesExactIntrinsicFromThePublishedIndexAndIgnoresPageNavigation() {
        val requests = ArrayList<String?>()
        val result: String =
            OfficialSlangDocs.search(OfficialSlangDocs.parse("", ""), "Saturate", null) { url: String? ->
                requests.add(url)
                if (url!!.endsWith("index.html")) {
                    """
                    <a href="../saturate.html">Wrong path</a>
                    <a href="saturate.html">saturate</a>
                    
                    """
                        .trimIndent()
                } else {
                    """
                    <nav>Unrelated navigation</nav>
                    <article role="main"><h1>saturate</h1><pre>float saturate(float x);</pre>
                    <p>Clamps x to [0, 1].</p></article>
                    
                    """
                        .trimIndent()
                }
            }
        Assertions.assertEquals(2, requests.size)
        Assertions.assertTrue(requests.last()!!.endsWith("/global-decls/saturate.html"), requests.toString())
        Assertions.assertTrue(result.contains("float saturate(float x);"), result)
        Assertions.assertFalse(result.contains("Unrelated navigation"), result)
    }

    @Test
    fun onlyExpandsRecognizedOfficialLinksAndReleaseTags() {
        Assertions.assertTrue(sources("https://shader-slang.org.example/slang/user-guide/compiling", null).isEmpty())
        Assertions.assertTrue(
            sources("https://github.com/shader-slang/slang/blob/master/docs/../test.md", null).isEmpty())
        Assertions.assertTrue(
            sources("https://github.com/shader-slang/slang/blob/master/docs/faq.md?x=1", null).isEmpty())
        Assertions.assertThrows<IllegalArgumentException?>(IllegalArgumentException::class.java) {
            releaseTag("../master")
        }
    }
}
