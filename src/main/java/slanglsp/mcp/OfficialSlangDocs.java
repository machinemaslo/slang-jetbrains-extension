package slanglsp.mcp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Searches the official LLM corpus locally and fetches selected official source pages. */
public final class OfficialSlangDocs {
    static final String INDEX = "https://shader-slang.org/docs/llms.txt";
    static final String REFERENCE = "https://shader-slang.org/docs/llms-full.txt";
    private static final int MAX_BYTES = 256 * 1024;
    private static final long CACHE_NANOS = Duration.ofHours(12).toNanos();
    private static final int MAX_CACHED_PAGES = 24;
    private static final LinkedHashMap<String, CachedPage> PAGES = new LinkedHashMap<>(32, 0.75f, true);
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\((https://[^\\s)]+)\\)(.*)");
    private static final Set<String> COMMON = Set.of("about", "and", "can", "does", "for", "how", "in", "is",
            "of", "slang", "the", "this", "to", "use", "using", "what", "with");
    private static Corpus cached;
    private static long loadedAt;

    record Entry(String heading, String excerpt, String url) {
    }

    record Corpus(List<Entry> entries) {
    }

    private record CachedPage(String text, long loadedAt) {
    }

    private OfficialSlangDocs() {
    }

    public static String lookup(String query, String sdkVersion) throws IOException, InterruptedException {
        if (query.isBlank() || query.length() > 240) {
            throw new IllegalArgumentException("Use a Slang topic or identifier (1–240 characters).");
        }
        String tag = LinkedDocs.releaseTag(sdkVersion);
        return search(load(), query, tag, OfficialSlangDocs::cachedDownload);
    }

    private static String cachedDownload(String url) throws IOException, InterruptedException {
        synchronized (PAGES) {
            var page = PAGES.get(url);
            if (page != null && System.nanoTime() - page.loadedAt() < CACHE_NANOS) {
                return page.text();
            }
        }
        String text = download(url);
        synchronized (PAGES) {
            PAGES.put(url, new CachedPage(text, System.nanoTime()));
            if (PAGES.size() > MAX_CACHED_PAGES) {
                PAGES.pollFirstEntry();
            }
        }
        return text;
    }

    private static synchronized Corpus load() throws IOException, InterruptedException {
        if (cached != null && System.nanoTime() - loadedAt < CACHE_NANOS) {
            return cached;
        }
        try {
            cached = parse(download(REFERENCE), download(INDEX));
            loadedAt = System.nanoTime();
            return cached;
        } catch (IOException failure) {
            if (cached != null) {
                return cached;
            }
            throw failure;
        }
    }

    private static String download(String url) throws IOException, InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Slang documentation returned HTTP " + connection.getResponseCode());
            }
            try (var input = connection.getInputStream()) {
                var bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new IOException("Slang documentation exceeds the download limit");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    static Corpus parse(String reference, String index) {
        var entries = new ArrayList<Entry>();
        String heading = "";
        var section = new StringBuilder();
        boolean fenced = false;
        for (String line : reference.split("\\R")) {
            var match = HEADING.matcher(line);
            if (!fenced && match.matches()) {
                addSection(entries, heading, section);
                heading = match.group(1);
                section.setLength(0);
            } else {
                section.append(line).append('\n');
                if (line.stripLeading().startsWith("```")) {
                    fenced = !fenced;
                }
            }
        }
        addSection(entries, heading, section);
        var links = new HashSet<String>();
        for (String line : index.split("\\R")) {
            var match = LINK.matcher(line);
            if (match.find() && links.add(match.group(2))) {
                entries.add(new Entry(match.group(1), match.group(3).replaceFirst("^\\s*:\\s*", "").strip(),
                        match.group(2)));
            }
        }
        return new Corpus(List.copyOf(entries));
    }

    private static void addSection(List<Entry> entries, String heading, StringBuilder section) {
        if (!heading.isBlank() && !heading.equals("Table of Contents") && !section.toString().isBlank()) {
            entries.add(new Entry(heading, section.toString().strip(), REFERENCE));
        }
    }

    static String search(Corpus corpus, String query) {
        try {
            return search(corpus, query, null, null);
        } catch (InterruptedException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String search(Corpus corpus, String query, String tag, LinkedDocs.Fetcher fetcher)
            throws InterruptedException {
        var terms = words(query);
        terms.removeAll(COMMON);
        String phrase = query.strip().toLowerCase(Locale.ROOT);
        var ranked = corpus.entries().stream()
                .map(entry -> new Match(entry, score(entry, terms, phrase)))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingInt(Match::score).reversed()).toList();
        var output = new StringBuilder("Official Slang documentation. Upstream results may differ from your SDK.\n");
        int emitted = 0;
        String expanded = null;
        if (fetcher != null) {
            var intrinsic = LinkedDocs.intrinsic(query, fetcher);
            if (intrinsic != null) {
                append(output, query.strip() + " (Core Module Reference)", intrinsic, terms, tag);
                emitted++;
            } else {
                var candidates = ranked.stream().map(Match::entry)
                        .filter(entry -> !LinkedDocs.sources(entry.url(), tag).isEmpty()).limit(2).toList();
                for (var candidate : candidates) {
                    var page = LinkedDocs.fetchLinked(candidate.url(), tag, fetcher);
                    if (page != null) {
                        append(output, candidate.heading(), page, terms, tag);
                        expanded = candidate.url();
                        emitted++;
                        break;
                    }
                }
            }
        }
        if (ranked.isEmpty() && emitted == 0) {
            return output.append("No direct match. See ").append(INDEX).append(" or the Core Module Reference: ")
                    .append("https://docs.shader-slang.org/en/latest/external/core-module-reference/").toString();
        }
        for (var result : ranked) {
            var entry = result.entry();
            if (entry.url().equals(expanded)) {
                continue;
            }
            if (emitted == 3) {
                break;
            }
            emitted++;
            output.append("\n").append(entry.heading()).append(" [upstream] — ").append(entry.url()).append('\n');
            if (!entry.excerpt().isBlank()) {
                output.append(excerpt(entry.excerpt(), terms)).append('\n');
            }
        }
        return output.toString().stripTrailing();
    }

    private static void append(StringBuilder output, String heading, LinkedDocs.Page page, Set<String> terms,
                               String tag) {
        output.append('\n').append(heading).append(page.matched() ? " [SDK " + tag + "]" : " [upstream]")
                .append(" — ").append(page.url()).append('\n').append(excerpt(page.text(), terms)).append('\n');
    }

    private record Match(Entry entry, int score) {
    }

    private static int score(Entry entry, Set<String> terms, String phrase) {
        var title = words(entry.heading());
        var body = words(entry.excerpt());
        int total = entry.heading().toLowerCase(Locale.ROOT).contains(phrase) ? 12 : 0;
        for (String term : terms) {
            total += title.contains(term) ? 5 : body.contains(term) ? 1 : 0;
        }
        return total;
    }

    private static Set<String> words(String string) {
        var terms = new LinkedHashSet<String>();
        var matcher = WORD.matcher(string.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            terms.add(matcher.group());
        }
        return terms;
    }

    private static String excerpt(String text, Set<String> terms) {
        int limit = 700;
        if (text.length() <= limit) {
            return text;
        }
        var paragraphs = text.split("\\n\\s*\\n");
        int best = 0;
        int most = -1;
        for (int i = 0; i < paragraphs.length; i++) {
            var matches = words(paragraphs[i]);
            matches.retainAll(terms);
            if (matches.size() > most) {
                most = matches.size();
                best = i;
            }
        }
        String snippet = String.join("\n\n", java.util.Arrays.copyOfRange(paragraphs, best, paragraphs.length));
        return (best == 0 ? "" : "…\n") + (snippet.length() <= limit ? snippet
                : snippet.substring(0, limit).stripTrailing() + "…");
    }
}
