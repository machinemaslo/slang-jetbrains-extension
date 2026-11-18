package slanglsp.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfficialSlangDocsTest {
    @Test
    void findsRelevantCitedExcerptsWithoutReturningTheWholeReference() {
        var content = OfficialSlangDocs.parse("""
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
                """, """
                - [Generics](https://docs.shader-slang.org/generics): Generic value parameters and constraints.
                """);
        String result = OfficialSlangDocs.search(content, "generic value parameters");
        assertTrue(result.contains(OfficialSlangDocs.REFERENCE), result);
        assertTrue(result.contains("https://docs.shader-slang.org/generics"), result);
        assertTrue(result.contains("run<let N : int>"), result);
        assertFalse(result.contains("Unrelated section"), result);
    }

    @Test
    void capsLongMatchesAndPointsUnmatchedQueriesAtTheOfficialIndex() {
        var content = OfficialSlangDocs.parse("## Generics\n" + "generic ".repeat(500), "");
        assertTrue(OfficialSlangDocs.search(content, "generics").length() < 1000);
        assertTrue(OfficialSlangDocs.search(content, "no-such-intrinsic").contains(OfficialSlangDocs.INDEX));
    }

    @Test
    void followsRankedGuideAndPrefersTheRequestedRelease() throws Exception {
        var corpus = OfficialSlangDocs.parse("## Generics\nAn upstream overview.", """
                - [Interfaces and Generics](https://shader-slang.com/slang/user-guide/interfaces-generics): Generic constraints.
                - [Unrelated](https://github.com/shader-slang/slang/tree/master/examples/triangle): Triangle.
                """);
        var requests = new ArrayList<String>();
        String result = OfficialSlangDocs.search(corpus, "generic constraints", LinkedDocs.releaseTag("2026.22"), url -> {
            requests.add(url);
            return """
                    ---
                    layout: user-guide
                    ---
                    A generic constraint limits the types you can pass.
                    """;
        });
        assertEquals(List.of("https://raw.githubusercontent.com/shader-slang/slang/v2026.22/docs/user-guide/06-interfaces-generics.md"),
                requests);
        assertTrue(result.contains("[SDK v2026.22]"), result);
        assertTrue(result.contains("limits the types"), result);
        assertFalse(result.contains("layout: user-guide"), result);
    }

    @Test
    void fallsBackToUpstreamThenPreservesTheIndexWhenNeitherPageIsAvailable() throws Exception {
        var corpus = OfficialSlangDocs.parse("", """
                - [Hello World Compute](https://github.com/shader-slang/slang/tree/master/examples/hello-world): Minimal compute example.
                """);
        String result = OfficialSlangDocs.search(corpus, "hello world", "v2026.22", url -> {
            if (url.contains("/v2026.22/")) {
                throw new IOException("not found");
            }
            return "A runnable Slang example.";
        });
        assertTrue(result.contains("[upstream]") && result.contains("/master/examples/hello-world/README.md"), result);
        String offline = OfficialSlangDocs.search(corpus, "hello world", "v2026.22", url -> {
            throw new IOException("unavailable");
        });
        assertTrue(offline.contains("Minimal compute example."), offline);
    }

    @Test
    void retrievesExactIntrinsicFromThePublishedIndexAndIgnoresPageNavigation() throws Exception {
        var requests = new ArrayList<String>();
        String result = OfficialSlangDocs.search(OfficialSlangDocs.parse("", ""), "Saturate", null, url -> {
            requests.add(url);
            return url.endsWith("index.html") ? """
                    <a href="../saturate.html">Wrong path</a>
                    <a href="saturate.html">saturate</a>
                    """ : """
                    <nav>Unrelated navigation</nav>
                    <article role="main"><h1>saturate</h1><pre>float saturate(float x);</pre>
                    <p>Clamps x to [0, 1].</p></article>
                    """;
        });
        assertEquals(2, requests.size());
        assertTrue(requests.getLast().endsWith("/global-decls/saturate.html"), requests.toString());
        assertTrue(result.contains("float saturate(float x);"), result);
        assertFalse(result.contains("Unrelated navigation"), result);
    }

    @Test
    void onlyExpandsRecognizedOfficialLinksAndReleaseTags() {
        assertTrue(LinkedDocs.sources("https://shader-slang.org.example/slang/user-guide/compiling", null).isEmpty());
        assertTrue(LinkedDocs.sources("https://github.com/shader-slang/slang/blob/master/docs/../test.md", null).isEmpty());
        assertTrue(LinkedDocs.sources("https://github.com/shader-slang/slang/blob/master/docs/faq.md?x=1", null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> LinkedDocs.releaseTag("../master"));
    }
}
