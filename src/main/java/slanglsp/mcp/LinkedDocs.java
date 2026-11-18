package slanglsp.mcp;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps recognized Slang doc links to bounded, read-only text sources. */
final class LinkedDocs {
    private static final String RAW = "https://raw.githubusercontent.com/shader-slang/slang/";
    private static final String CORE = "https://docs.shader-slang.org/en/latest/external/core-module-reference/";
    private static final Pattern VERSION = Pattern.compile("v?(20[0-9]{2}\\.[0-9]{1,3}(?:\\.[0-9]{1,3})?)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z_0-9]{0,79}");
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---[ \\t]*\\R.*?^---[ \\t]*(?:\\R|\\z)",
            Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern ARTICLE = Pattern.compile("<article\\b[^>]*>(.*?)</article>", Pattern.DOTALL);
    private static final Pattern CORE_LINK = Pattern.compile("href=\"([a-zA-Z0-9_./-]+\\.html)\"");
    private static final Map<String, String> GUIDE = Map.ofEntries(
            Map.entry("get-started", "01-get-started"),
            Map.entry("convenience-features", "03-convenience-features"),
            Map.entry("modules-and-access-control", "04-modules-and-access-control"),
            Map.entry("capabilities", "05-capabilities"),
            Map.entry("interfaces-generics", "06-interfaces-generics"),
            Map.entry("autodiff", "07-autodiff"),
            Map.entry("compiling", "08-compiling"),
            Map.entry("reflection", "09-reflection"),
            Map.entry("targets", "09-targets"),
            Map.entry("link-time-specialization", "10-link-time-specialization"));

    private LinkedDocs() {
    }

    @FunctionalInterface
    interface Fetcher {
        String fetch(String url) throws IOException, InterruptedException;
    }

    record Page(String url, String text, boolean matched) {
    }

    static String releaseTag(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        var match = VERSION.matcher(version.strip());
        if (!match.matches()) {
            throw new IllegalArgumentException("Use a Slang release version such as 2026.22.");
        }
        return "v" + match.group(1);
    }

    static List<String> sources(String link, String tag) {
        URI uri;
        try {
            uri = URI.create(link);
        } catch (IllegalArgumentException malformed) {
            return List.of();
        }
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null) {
            return List.of();
        }
        String path = uri.getRawPath();
        String file = null;
        if ("shader-slang.org".equals(uri.getHost()) || "shader-slang.com".equals(uri.getHost())) {
            String prefix = "/slang/user-guide/";
            if (path.startsWith(prefix)) {
                String slug = path.substring(prefix.length()).replaceFirst("/$", "");
                String name = GUIDE.get(slug);
                if (name != null) {
                    file = "docs/user-guide/" + name + ".md";
                }
            }
        } else if ("github.com".equals(uri.getHost())) {
            String blob = "/shader-slang/slang/blob/master/";
            String tree = "/shader-slang/slang/tree/master/";
            if (path.startsWith(blob)) {
                String candidate = path.substring(blob.length());
                if (candidate.matches("docs/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.md")) {
                    file = candidate;
                }
            } else if (path.startsWith(tree)) {
                String candidate = path.substring(tree.length()).replaceFirst("/$", "");
                if (candidate.matches("examples/[A-Za-z0-9_-]+")) {
                    file = candidate + "/README.md";
                }
            }
        }
        if (file == null) {
            return List.of();
        }
        return tag == null ? List.of(RAW + "master/" + file)
                : List.of(RAW + tag + "/" + file, RAW + "master/" + file);
    }

    static Page fetchLinked(String link, String tag, Fetcher fetcher) throws InterruptedException {
        for (String source : sources(link, tag)) {
            try {
                String body = FRONT_MATTER.matcher(fetcher.fetch(source)).replaceFirst("").strip();
                if (!body.isEmpty()) {
                    return new Page(source, body, tag != null && source.startsWith(RAW + tag + "/"));
                }
            } catch (IOException unavailable) {
                // Try upstream, or leave the original index entry available.
            }
        }
        return null;
    }

    static Page intrinsic(String query, Fetcher fetcher) throws InterruptedException {
        String name = query.strip();
        if (!IDENTIFIER.matcher(name).matches()) {
            return null;
        }
        try {
            String directory = CORE + "global-decls/";
            var links = CORE_LINK.matcher(fetcher.fetch(directory + "index.html"));
            while (links.find()) {
                String link = links.group(1);
                if (link.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT) + ".html")) {
                    String url = directory + link;
                    String text = articleText(fetcher.fetch(url));
                    return text.isBlank() ? null : new Page(url, text, false);
                }
            }
        } catch (IOException unavailable) {
            // The LLM corpus remains available even when the generated reference is not.
        }
        return null;
    }

    static String articleText(String html) throws IOException {
        var article = ARTICLE.matcher(html);
        if (!article.find()) {
            return "";
        }
        var text = new StringBuilder();
        new ParserDelegator().parse(new StringReader(article.group(1)), new HTMLEditorKit.ParserCallback() {
            @Override
            public void handleText(char[] chars, int position) {
                text.append(chars);
            }

            @Override
            public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag.isBlock()) {
                    text.append('\n');
                }
            }

            @Override
            public void handleEndTag(HTML.Tag tag, int position) {
                if (tag.isBlock()) {
                    text.append('\n');
                }
            }
        }, true);
        return text.toString().replace("¶", "").replaceAll("\\n{3,}", "\n\n").strip();
    }
}
