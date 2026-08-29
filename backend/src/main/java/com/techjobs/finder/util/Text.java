package com.techjobs.finder.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;

/** Normalização e sanitização de texto vindo de fontes externas. */
public final class Text {

    private static final Set<String> COMPANY_SUFFIXES =
            Set.of("ltda", "sa", "s a", "inc", "llc", "gmbh", "bv", "ab", "corp", "co", "me", "eireli");

    private Text() {
    }

    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Minúsculas, sem acentos, espaços colapsados. Base de todas as comparações. */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#.\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Remove sufixos societários para que "ACME Ltda" e "Acme" virem a mesma empresa. */
    public static String normalizeCompany(String value) {
        String normalized = normalize(value);
        if (normalized == null || normalized.isEmpty()) {
            return normalized;
        }
        String[] parts = normalized.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String clean = part.replace(".", "");
            if (COMPANY_SUFFIXES.contains(clean)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(clean);
        }
        String result = builder.toString().trim();
        return result.isEmpty() ? normalized : result;
    }

    /**
     * Converte HTML de descrição em texto puro; nenhuma tag sobrevive.
     *
     * <p>Algumas fontes entregam o HTML escapado (por exemplo {@code &lt;p&gt;}): uma única
     * passada apenas revelaria as tags em vez de removê-las. Por isso a limpeza se repete
     * enquanto o resultado ainda parecer marcação, com um teto de passadas.
     */
    public static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        // Jsoup.parse().text() já descarta tags, conteúdo de script/style e converte entidades,
        // preservando o espaço entre blocos. Repetir resolve o HTML escapado mais de uma vez.
        String current = html;
        for (int pass = 0; pass < 3; pass++) {
            String cleaned = Jsoup.parse(current).text();
            boolean stable = cleaned.equals(current);
            current = cleaned;
            if (stable || !looksLikeMarkup(current)) {
                break;
            }
        }
        return blankToNull(current);
    }

    private static boolean looksLikeMarkup(String value) {
        return value.contains("<") && value.contains(">");
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    /**
     * Similaridade de Jaccard entre os tokens de dois textos. Usada na deduplicação por título.
     * Retorna valor entre 0 e 1.
     */
    public static double tokenSimilarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        // HashSet, não Set.of: tokens repetidos no título fariam Set.of lançar.
        Set<String> tokensA = new HashSet<>(Arrays.asList(a.split(" ")));
        Set<String> tokensB = new HashSet<>(Arrays.asList(b.split(" ")));
        long intersection = tokensA.stream().filter(tokensB::contains).count();
        int union = tokensA.size() + tokensB.size() - (int) intersection;
        return union == 0 ? 0d : (double) intersection / union;
    }
}
