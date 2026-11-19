package slanglsp.mcp

import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.util.regex.Pattern
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit.ParserCallback
import javax.swing.text.html.parser.ParserDelegator

/** Maps recognized Slang doc links to bounded, read-only text sources. */
internal object LinkedDocs {
    private const val RAW = "https://raw.githubusercontent.com/shader-slang/slang/"
    private const val CORE = "https://docs.shader-slang.org/en/latest/external/core-module-reference/"
    private val VERSION: Pattern = Pattern.compile("v?(20[0-9]{2}\\.[0-9]{1,3}(?:\\.[0-9]{1,3})?)")
    private val IDENTIFIER: Pattern = Pattern.compile("[A-Za-z_][A-Za-z_0-9]{0,79}")
    private val FRONT_MATTER: Pattern =
        Pattern.compile("\\A---[ \\t]*\\R.*?^---[ \\t]*(?:\\R|\\z)", Pattern.DOTALL or Pattern.MULTILINE)
    private val ARTICLE: Pattern = Pattern.compile("<article\\b[^>]*>(.*?)</article>", Pattern.DOTALL)
    private val CORE_LINK: Pattern = Pattern.compile("href=\"([a-zA-Z0-9_./-]+\\.html)\"")
    private val GUIDE =
        mapOf(
            "get-started" to "01-get-started",
            "convenience-features" to "03-convenience-features",
            "modules-and-access-control" to "04-modules-and-access-control",
            "capabilities" to "05-capabilities",
            "interfaces-generics" to "06-interfaces-generics",
            "autodiff" to "07-autodiff",
            "compiling" to "08-compiling",
            "reflection" to "09-reflection",
            "targets" to "09-targets",
            "link-time-specialization" to "10-link-time-specialization")

    @JvmStatic
    fun releaseTag(version: String?): String? {
        if (version.isNullOrBlank()) {
            return null
        }
        val match = VERSION.matcher(version.trim())
        require(match.matches()) { "Use a Slang release version such as 2026.22." }
        return "v" + match.group(1)
    }

    @JvmStatic
    fun sources(link: String, tag: String?): List<String> {
        val uri: URI
        try {
            uri = URI.create(link)
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        if (("https" != uri.scheme) || uri.getUserInfo() != null || uri.port != -1 || uri.rawQuery != null) {
            return emptyList()
        }
        val path = uri.rawPath
        var file: String? = null
        if ("shader-slang.org" == uri.host || "shader-slang.com" == uri.host) {
            val prefix = "/slang/user-guide/"
            if (path.startsWith(prefix)) {
                val slug = path.substring(prefix.length).replaceFirst("/$".toRegex(), "")
                val name = GUIDE[slug]
                if (name != null) {
                    file = "docs/user-guide/$name.md"
                }
            }
        } else if ("github.com" == uri.host) {
            val blob = "/shader-slang/slang/blob/master/"
            val tree = "/shader-slang/slang/tree/master/"
            if (path.startsWith(blob)) {
                val candidate = path.substring(blob.length)
                if (candidate.matches("docs/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.md".toRegex())) {
                    file = candidate
                }
            } else if (path.startsWith(tree)) {
                val candidate = path.substring(tree.length).replaceFirst("/$".toRegex(), "")
                if (candidate.matches("examples/[A-Za-z0-9_-]+".toRegex())) {
                    file = "$candidate/README.md"
                }
            }
        }
        if (file == null) {
            return emptyList()
        }
        return if (tag == null) listOf("${RAW}master/$file") else listOf("$RAW$tag/$file", "${RAW}master/$file")
    }

    @Throws(InterruptedException::class)
    fun fetchLinked(link: String, tag: String?, fetcher: Fetcher): Page? {
        for (source in sources(link, tag)) {
            try {
                val body = FRONT_MATTER.matcher(fetcher.fetch(source)).replaceFirst("").trim()
                if (body.isNotEmpty()) {
                    return Page(source, body, tag != null && source.startsWith("$RAW$tag/"))
                }
            } catch (_: IOException) {
                // Try upstream, or leave the original index entry available.
            }
        }
        return null
    }

    @Throws(InterruptedException::class)
    fun intrinsic(query: String, fetcher: Fetcher): Page? {
        val name = query.trim()
        if (!IDENTIFIER.matcher(name).matches()) {
            return null
        }
        try {
            val directory = CORE + "global-decls/"
            val links = CORE_LINK.matcher(fetcher.fetch(directory + "index.html"))
            while (links.find()) {
                val link = links.group(1)
                if (link.lowercase() == name.lowercase() + ".html") {
                    val url = directory + link
                    val text = articleText(fetcher.fetch(url))
                    return if (text.isBlank()) null else Page(url, text, false)
                }
            }
        } catch (_: IOException) {
            // The LLM corpus remains available even when the generated reference is not.
        }
        return null
    }

    @Throws(IOException::class)
    fun articleText(html: String): String {
        val article = ARTICLE.matcher(html)
        if (!article.find()) {
            return ""
        }
        val text = StringBuilder()
        ParserDelegator()
            .parse(
                StringReader(article.group(1)),
                object : ParserCallback() {
                    override fun handleText(chars: CharArray, position: Int) {
                        text.append(chars)
                    }

                    override fun handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet?, position: Int) {
                        if (tag.isBlock) {
                            text.append('\n')
                        }
                    }

                    override fun handleEndTag(tag: HTML.Tag, position: Int) {
                        if (tag.isBlock) {
                            text.append('\n')
                        }
                    }
                },
                true)
        return text.toString().replace("¶", "").replace("\\n{3,}".toRegex(), "\n\n").trim()
    }

    internal fun interface Fetcher {
        @Throws(IOException::class, InterruptedException::class) fun fetch(url: String): String
    }

    @JvmRecord internal data class Page(val url: String, val text: String, val matched: Boolean)
}
