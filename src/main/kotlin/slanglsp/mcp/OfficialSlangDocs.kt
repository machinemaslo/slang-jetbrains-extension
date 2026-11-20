package slanglsp.mcp

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.regex.Pattern
import kotlin.AssertionError
import kotlin.Int
import kotlin.Long
import kotlin.Throws
import kotlin.require
import kotlin.synchronized
import kotlin.text.StringBuilder
import kotlin.text.contains
import kotlin.text.isBlank
import kotlin.text.isEmpty
import kotlin.text.lowercase
import kotlin.text.replaceFirst
import kotlin.text.split
import kotlin.text.startsWith
import kotlin.text.substring
import kotlin.text.toRegex
import kotlin.text.trim
import kotlin.text.trimEnd
import kotlin.text.trimStart

/** Searches the official LLM corpus locally and fetches selected official source pages. */
internal object OfficialSlangDocs {
    const val INDEX: String = "https://shader-slang.org/docs/llms.txt"
    const val REFERENCE: String = "https://shader-slang.org/docs/llms-full.txt"
    private const val MAX_BYTES = 256 * 1024
    private val CACHE_NANOS = Duration.ofHours(12).toNanos()
    private const val MAX_CACHED_PAGES = 24
    private val PAGES: LinkedHashMap<String?, CachedPage?> = LinkedHashMap(32, 0.75f, true)
    private val HEADING: Pattern = Pattern.compile("^#{1,6}\\s+(.+)$")
    private val WORD: Pattern = Pattern.compile("[\\p{L}\\p{N}_]+")
    private val LINK: Pattern = Pattern.compile("\\[([^]]+)]\\((https://[^\\s)]+)\\)(.*)")
    private val COMMON =
        mutableSetOf<String?>(
            "about",
            "and",
            "can",
            "does",
            "for",
            "how",
            "in",
            "is",
            "of",
            "slang",
            "the",
            "this",
            "to",
            "use",
            "using",
            "what",
            "with")
    private var cached: Corpus? = null
    private var loadedAt: Long = 0

    @Throws(IOException::class, InterruptedException::class)
    fun lookup(query: String, sdkVersion: String?): String {
        require(!(query.isBlank() || query.length > 240)) { "Use a Slang topic or identifier (1–240 characters)." }
        val tag = LinkedDocs.releaseTag(sdkVersion)
        return search(load(), query, tag, LinkedDocs.Fetcher(::cachedDownload))
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun cachedDownload(url: String): String {
        synchronized(PAGES) {
            val page = PAGES[url]
            if (page != null && System.nanoTime() - page.loadedAt < CACHE_NANOS) {
                return page.text
            }
        }
        val text = download(url)
        synchronized(PAGES) {
            PAGES[url] = CachedPage(text, System.nanoTime())
            if (PAGES.size > MAX_CACHED_PAGES) {
                PAGES.pollFirstEntry()
            }
        }
        return text
    }

    @Synchronized
    @Throws(IOException::class, InterruptedException::class)
    private fun load(): Corpus {
        if (cached != null && System.nanoTime() - loadedAt < CACHE_NANOS) {
            cached?.let {
                return it
            }
        }
        try {
            val corpus = parse(download(REFERENCE), download(INDEX))
            cached = corpus
            loadedAt = System.nanoTime()
            return corpus
        } catch (failure: IOException) {
            cached?.let {
                return it
            }
            throw failure
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun download(url: String): String {
        if (Thread.interrupted()) {
            throw InterruptedException()
        }
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.setInstanceFollowRedirects(false)
        connection.setConnectTimeout(3000)
        connection.setReadTimeout(3000)
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw IOException("Slang documentation returned HTTP " + connection.getResponseCode())
            }
            connection.getInputStream().use { input ->
                val bytes = input.readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) {
                    throw IOException("Slang documentation exceeds the download limit")
                }
                return String(bytes, StandardCharsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun parse(reference: String, index: String): Corpus {
        val entries = ArrayList<Entry>()
        var heading = ""
        val section = StringBuilder()
        var fenced = false
        for (line in reference.split("\\R".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            val match = HEADING.matcher(line)
            if (!fenced && match.matches()) {
                addSection(entries, heading, section)
                heading = match.group(1)
                section.setLength(0)
            } else {
                section.append(line).append('\n')
                if (line.trimStart().startsWith("```")) {
                    fenced = !fenced
                }
            }
        }
        addSection(entries, heading, section)
        val links = HashSet<String?>()
        for (line in index.split("\\R".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            val match = LINK.matcher(line)
            if (match.find() && links.add(match.group(2))) {
                entries.add(
                    Entry(
                        match.group(1), match.group(3).replaceFirst("^\\s*:\\s*".toRegex(), "").trim(), match.group(2)))
            }
        }
        return Corpus(entries.toList())
    }

    private fun addSection(entries: MutableList<Entry>, heading: String, section: StringBuilder) {
        if (heading.isNotBlank() && (heading != "Table of Contents") && section.toString().isNotBlank()) {
            entries.add(Entry(heading, section.toString().trim(), REFERENCE))
        }
    }

    fun search(corpus: Corpus, query: String): String {
        try {
            return search(corpus, query, null, null)
        } catch (impossible: InterruptedException) {
            throw AssertionError(impossible)
        }
    }

    @Throws(InterruptedException::class)
    fun search(corpus: Corpus, query: String, tag: String?, fetcher: LinkedDocs.Fetcher?): String {
        val terms = words(query)
        terms.removeAll(COMMON)
        val phrase = query.trim().lowercase()
        val ranked =
            corpus.entries
                .map { entry -> Match(entry, score(entry, terms, phrase)) }
                .filter { it.score > 0 }
                .sortedByDescending { it.score }
        val output = StringBuilder("Official Slang documentation. Upstream results may differ from your SDK.\n")
        var emitted = 0
        var expanded: String? = null
        if (fetcher != null) {
            val intrinsic = LinkedDocs.intrinsic(query, fetcher)
            if (intrinsic != null) {
                append(output, query.trim() + " (Core Module Reference)", intrinsic, terms, tag)
                emitted++
            } else {
                val candidates =
                    ranked
                        .asSequence()
                        .map(Match::entry)
                        .filter { LinkedDocs.sources(it.url, tag).isNotEmpty() }
                        .take(2)
                        .toList()
                for ((heading, _, url) in candidates) {
                    val page = LinkedDocs.fetchLinked(url, tag, fetcher)
                    if (page != null) {
                        append(output, heading, page, terms, tag)
                        expanded = url
                        emitted++
                        break
                    }
                }
            }
        }
        if (ranked.isEmpty() && emitted == 0) {
            return output
                .append("No direct match. See ")
                .append(INDEX)
                .append(" or the Core Module Reference: ")
                .append("https://docs.shader-slang.org/en/latest/external/core-module-reference/")
                .toString()
        }
        for (result in ranked) {
            val entry = result.entry
            if (entry.url == expanded) {
                continue
            }
            if (emitted == 3) {
                break
            }
            emitted++
            output.append("\n").append(entry.heading).append(" [upstream] — ").append(entry.url).append('\n')
            if (entry.excerpt.isNotBlank()) {
                output.append(excerpt(entry.excerpt, terms)).append('\n')
            }
        }
        return output.toString().trimEnd()
    }

    private fun append(
        output: StringBuilder,
        heading: String?,
        page: LinkedDocs.Page,
        terms: MutableSet<String?>,
        tag: String?
    ) {
        output
            .append('\n')
            .append(heading)
            .append(if (page.matched) " [SDK $tag]" else " [upstream]")
            .append(" — ")
            .append(page.url)
            .append('\n')
            .append(excerpt(page.text, terms))
            .append('\n')
    }

    private fun score(entry: Entry, terms: MutableSet<String?>, phrase: String): Int {
        val title = words(entry.heading)
        val body = words(entry.excerpt)
        var total = if (entry.heading.lowercase().contains(phrase)) 12 else 0
        for (term in terms) {
            total += if (title.contains(term)) 5 else if (body.contains(term)) 1 else 0
        }
        return total
    }

    private fun words(string: String): MutableSet<String?> {
        val terms = LinkedHashSet<String?>()
        val matcher = WORD.matcher(string.lowercase())
        while (matcher.find()) {
            terms.add(matcher.group())
        }
        return terms
    }

    private fun excerpt(text: String, terms: MutableSet<String?>): String {
        val limit = 700
        if (text.length <= limit) {
            return text
        }
        val paragraphs = text.split("\\n\\s*\\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var best = 0
        var most = -1
        for (i in paragraphs.indices) {
            val matches = words(paragraphs[i])
            matches.retainAll(terms)
            if (matches.size > most) {
                most = matches.size
                best = i
            }
        }
        val snippet = paragraphs.drop(best).joinToString("\n\n")
        return (if (best == 0) "" else "…\n") +
            (if (snippet.length <= limit) snippet else snippet.substring(0, limit).trimEnd() + "…")
    }

    @JvmRecord internal data class Entry(val heading: String, val excerpt: String, val url: String)

    @JvmRecord internal data class Corpus(val entries: List<Entry>)

    @JvmRecord private data class CachedPage(val text: String, val loadedAt: Long)

    @JvmRecord private data class Match(val entry: Entry, val score: Int)
}
