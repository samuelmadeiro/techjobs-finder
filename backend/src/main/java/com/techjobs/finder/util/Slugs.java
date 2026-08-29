package com.techjobs.finder.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Conversão entre nome de exibição e slug de tecnologia. */
public final class Slugs {

    /** Nomes com grafia própria que o slug não recupera sozinho. */
    private static final Map<String, String> DISPLAY_OVERRIDES = Map.ofEntries(
            Map.entry("csharp", "C#"),
            Map.entry("cpp", "C++"),
            Map.entry("c", "C"),
            Map.entry("nodejs", "Node.js"),
            Map.entry("dotnet", ".NET"),
            Map.entry("javascript", "JavaScript"),
            Map.entry("typescript", "TypeScript"),
            Map.entry("postgresql", "PostgreSQL"),
            Map.entry("mysql", "MySQL"),
            Map.entry("mongodb", "MongoDB"),
            Map.entry("aws", "AWS"),
            Map.entry("gcp", "GCP"),
            Map.entry("php", "PHP"),
            Map.entry("sql", "SQL"),
            Map.entry("spring-boot", "Spring Boot"),
            Map.entry("react", "React"),
            Map.entry("nextjs", "Next.js"),
            Map.entry("vuejs", "Vue.js"));

    private Slugs() {
    }

    /**
     * Gera o slug canônico. {@code C#} vira {@code csharp}, {@code C++} vira {@code cpp},
     * {@code Node.js} vira {@code nodejs}, {@code Spring Boot} vira {@code spring-boot}.
     */
    public static String toSlug(String value) {
        String text = Text.blankToNull(value);
        if (text == null) {
            return null;
        }
        String lowered = text.toLowerCase(Locale.ROOT).trim();
        String replaced = lowered
                .replace("c#", "csharp")
                .replace("c++", "cpp")
                .replace(".net", "dotnet")
                .replace("node.js", "nodejs")
                .replace("next.js", "nextjs")
                .replace("vue.js", "vuejs")
                .replace("objective-c", "objectivec");
        String normalized = Text.normalize(replaced);
        if (normalized == null) {
            return null;
        }
        String slug = normalized
                .replace(".", "")
                .replace("+", "")
                .replace("#", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? null : slug;
    }

    public static List<String> normalizeAll(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            // Aceita ?language=java,go além de ?language=java&language=go.
            for (String part : value.split(",")) {
                String slug = toSlug(part);
                if (slug != null && !result.contains(slug)) {
                    result.add(slug);
                }
            }
        }
        return List.copyOf(result);
    }

    /** Nome legível a partir do slug, para montar o texto enviado às fontes. */
    public static String toDisplay(String slug) {
        if (slug == null) {
            return null;
        }
        String override = DISPLAY_OVERRIDES.get(slug);
        if (override != null) {
            return override;
        }
        String[] parts = slug.split("-");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
