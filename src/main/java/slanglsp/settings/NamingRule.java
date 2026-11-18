package slanglsp.settings;

import java.util.*;
import java.util.regex.Pattern;

/** Immutable, ordered code-style rule. The first matching category wins. */
public record NamingRule(String name, Set<Category> categories, List<Variant> variants, boolean enabled) {
    public static final String ABBREVIATIONS = "GPU, CPU, API, UV, ID, BRDF, SSA, D3D";

    public record Variant(Style style, String prefix, String suffix) {
    }

    public NamingRule {
        categories = categories.contains(Category.ALL) ? Set.of(Category.ALL) : Set.copyOf(categories);
        variants = List.copyOf(variants);
        if (categories.isEmpty() || variants.isEmpty()) {
            throw new IllegalArgumentException("A naming rule needs at least one category and style");
        }
    }

    public NamingRule(String name, Category category, Style style, String prefix, String suffix) {
        this(name, Set.of(category), List.of(new Variant(style, prefix, suffix)), true);
    }

    public Category category() {
        return categories.stream().min(Comparator.naturalOrder()).orElseThrow();
    }

    public boolean matches(Category category) {
        return categories.contains(Category.ALL) || categories.contains(category);
    }

    public enum Category {
        TYPES, INTERFACES, ENUMS, ENUM_MEMBERS, NAMESPACES, FUNCTIONS, METHODS,
        FIELDS, GLOBALS, CONSTANTS, GENERIC_PARAMETERS, PARAMETERS, LOCALS, ALL
    }

    public enum Style {
        SNAKE_CASE, LOWER_CAMEL_CASE, UPPER_CAMEL_CASE, SCREAMING_SNAKE_CASE, ANY
    }

    private static final Pattern WORDS = Pattern.compile(
            "[A-Z]+(?=[A-Z][a-z]|[0-9]|$)|[A-Z]?[a-z]+|[A-Z]+|[0-9]+");

    public String suggest(String identifier, String abbreviations) {
        if (!enabled) {
            return identifier;
        }
        for (Variant variant : variants) {
            if (suggest(variant, identifier, abbreviations).equals(identifier)) {
                return identifier;
            }
        }
        return suggest(variants.getFirst(), identifier, abbreviations);
    }

    private static String suggest(Variant variant, String identifier, String abbreviations) {
        String prefix = variant.prefix(), suffix = variant.suffix();
        Style style = variant.style();
        String base = identifier;
        if (!prefix.isEmpty() && base.startsWith(prefix) && (prefix.length() > 1 || prefix.equals("_")
                || base.length() > 1 && Character.isUpperCase(base.charAt(1)))) {
            base = base.substring(prefix.length());
        }
        if (!suffix.isEmpty() && base.endsWith(suffix)) {
            base = base.substring(0, base.length() - suffix.length());
        }
        Set<String> acronyms = new HashSet<>();
        for (String word : abbreviations.split("[,;\\s]+")) {
            acronyms.add(word.toUpperCase(Locale.ROOT));
        }
        List<String> words = new ArrayList<>();
        String acronymPattern = acronyms.stream().filter(word -> !word.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(word -> "(?i:" + Pattern.quote(word) + ")(?=[A-Z_0-9]|$)")
                .collect(java.util.stream.Collectors.joining("|"));
        var matcher = (acronymPattern.isEmpty() ? WORDS
                : Pattern.compile(acronymPattern + "|" + WORDS.pattern())).matcher(base);
        while (matcher.find()) {
            words.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        if (words.isEmpty() && style != Style.ANY) {
            return identifier;
        }
        String converted = switch (style) {
            case SNAKE_CASE -> String.join("_", words);
            case SCREAMING_SNAKE_CASE -> String.join("_", words).toUpperCase(Locale.ROOT);
            case LOWER_CAMEL_CASE, UPPER_CAMEL_CASE -> {
                var result = new StringBuilder();
                for (int i = 0; i < words.size(); i++) {
                    String word = words.get(i);
                    if (i == 0 && style == Style.LOWER_CAMEL_CASE) {
                        result.append(word);
                    } else if (acronyms.contains(word.toUpperCase(Locale.ROOT))) {
                        result.append(word.toUpperCase(Locale.ROOT));
                    } else {
                        result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                    }
                }
                yield result.toString();
            }
            case ANY -> base;
        };
        return prefix + converted + suffix;
    }

    public static List<NamingRule> defaults(boolean camel) {
        if (camel) {
            return slang();
        }
        List<NamingRule> rules = new ArrayList<>();
        for (Category category : Category.values()) {
            if (category == Category.ALL) {
                continue;
            }
            Style style = category == Category.GENERIC_PARAMETERS ? Style.UPPER_CAMEL_CASE : Style.SNAKE_CASE;
            rules.add(new NamingRule(label(category), category, style, "", ""));
        }
        return List.copyOf(rules);
    }

    /** Slang's documented recommendations, checked against its shader examples and enum tests. */
    public static List<NamingRule> slang() {
        List<NamingRule> rules = new ArrayList<>();
        for (Category category : Category.values()) {
            if (category == Category.ALL) {
                continue;
            }
            Style style = switch (category) {
                case TYPES, INTERFACES, ENUMS, ENUM_MEMBERS, NAMESPACES, GENERIC_PARAMETERS,
                        GLOBALS, CONSTANTS -> Style.UPPER_CAMEL_CASE;
                default -> Style.LOWER_CAMEL_CASE;
            };
            String prefix = switch (category) {
                case INTERFACES -> "I";
                case GLOBALS -> "g";
                case CONSTANTS -> "k";
                default -> "";
            };
            rules.add(new NamingRule(label(category), category, style, prefix, ""));
        }
        return List.copyOf(rules);
    }

    public static String example(Category category) {
        return switch (category) {
            case TYPES -> "SurfaceGeometry";
            case INTERFACES -> "ITransformer";
            case ENUMS -> "Fruit";
            case ENUM_MEMBERS -> "Orange";
            case NAMESPACES -> "Slang";
            case FUNCTIONS -> "computeLighting";
            case METHODS -> "transform";
            case FIELDS -> "diffuseColor";
            case GLOBALS -> "gViewParams";
            case CONSTANTS -> "kSampleCount";
            case GENERIC_PARAMETERS -> "T";
            case PARAMETERS -> "inputValue";
            case LOCALS -> "worldPosition";
            case ALL -> "sampleName";
        };
    }

    public static String label(Category category) {
        return switch (category) {
            case TYPES -> slanglsp.SlangBundle.message("naming.category.types");
            case INTERFACES -> slanglsp.SlangBundle.message("naming.category.interfaces");
            case ENUMS -> slanglsp.SlangBundle.message("naming.category.enums");
            case ENUM_MEMBERS -> slanglsp.SlangBundle.message("naming.category.enum_members");
            case NAMESPACES -> slanglsp.SlangBundle.message("naming.category.namespaces");
            case FUNCTIONS -> slanglsp.SlangBundle.message("naming.category.functions");
            case METHODS -> slanglsp.SlangBundle.message("naming.category.methods");
            case FIELDS -> slanglsp.SlangBundle.message("naming.category.fields");
            case GLOBALS -> slanglsp.SlangBundle.message("naming.category.globals");
            case CONSTANTS -> slanglsp.SlangBundle.message("naming.category.constants");
            case GENERIC_PARAMETERS -> slanglsp.SlangBundle.message("naming.category.generic_parameters");
            case PARAMETERS -> slanglsp.SlangBundle.message("naming.category.parameters");
            case LOCALS -> slanglsp.SlangBundle.message("naming.category.locals");
            case ALL -> slanglsp.SlangBundle.message("naming.category.all");
        };
    }

    public static String label(Style style) {
        return switch (style) {
            case SNAKE_CASE -> slanglsp.SlangBundle.message("naming.style.snake_case");
            case LOWER_CAMEL_CASE -> slanglsp.SlangBundle.message("naming.style.lower_camel_case");
            case UPPER_CAMEL_CASE -> slanglsp.SlangBundle.message("naming.style.upper_camel_case");
            case SCREAMING_SNAKE_CASE -> slanglsp.SlangBundle.message("naming.style.screaming_snake_case");
            case ANY -> slanglsp.SlangBundle.message("naming.style.any");
        };
    }
}
