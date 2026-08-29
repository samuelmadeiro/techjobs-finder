package com.techjobs.finder.service;

import com.techjobs.finder.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * Transforma o HTML do anúncio em texto legível e separa requisitos de diferenciais.
 *
 * <p>A separação é feita sobre a árvore HTML, não sobre o texto achatado: as fontes
 * marcam a lista de requisitos com {@code <ul><li>} logo abaixo de um título, e essa
 * estrutura é o sinal mais confiável que existe. Achatar antes destruiria justamente
 * a informação que queremos.
 *
 * <p>Quando o anúncio não tem lista nenhuma, cai para a leitura por linhas com marcador.
 */
@Component
public class JobDescriptionParser {

    private static final Pattern REQUIRED_HEADING = Pattern.compile(
            "(?i)(requisitos|requirements|qualifica|what you.ll need|o que esperamos|"
                    + "pre[- ]?requisitos|must have|responsabilidades|skills? (?:and|&) experience)");

    private static final Pattern NICE_HEADING = Pattern.compile(
            "(?i)(diferenciais|nice to have|desej[aá]ve|bonus|ser[aá] um plus|plus|"
                    + "good to have|opcional|preferred qualifications)");

    /** Título que encerra a lista corrente sem abrir outra de interesse. */
    private static final Pattern STOP_HEADING = Pattern.compile(
            "(?i)(benef[ií]cios|benefits|sobre (a|n[oó]s)|about (us|the)|como se candidatar|"
                    + "how to apply|sal[aá]rio|compensation|perks|nossa cultura)");

    private static final int MAX_ITEMS = 25;
    private static final int MAX_ITEM_LENGTH = 500;
    /** "Git", "AWS" e "SQL" são requisitos legítimos: o piso não pode cortar siglas. */
    private static final int MIN_ITEM_LENGTH = 2;
    private static final int SHORT_DESCRIPTION_LENGTH = 400;

    /**
     * @param plainText    descrição completa em texto, com quebras de linha preservadas
     * @param shortText    abertura do anúncio, para o card da listagem
     * @param requirements itens listados como exigência
     * @param niceToHave   itens listados como diferencial
     */
    public record ParsedDescription(
            String plainText,
            String shortText,
            List<String> requirements,
            List<String> niceToHave) {

        public static ParsedDescription empty() {
            return new ParsedDescription(null, null, List.of(), List.of());
        }
    }

    public ParsedDescription parse(String descriptionHtml) {
        if (descriptionHtml == null || descriptionHtml.isBlank()) {
            return ParsedDescription.empty();
        }

        Document document = Jsoup.parse(unescapeIfNeeded(descriptionHtml));
        String plain = toPlainText(document);
        if (plain.isBlank()) {
            return ParsedDescription.empty();
        }

        List<String> required = new ArrayList<>();
        List<String> nice = new ArrayList<>();
        collectFromLists(document, required, nice);
        if (required.isEmpty() && nice.isEmpty()) {
            collectFromLines(plain, required, nice);
        }

        return new ParsedDescription(plain, shorten(plain), List.copyOf(required), List.copyOf(nice));
    }

    /**
     * Algumas fontes entregam o HTML escapado ({@code &lt;li&gt;}). Sem desescapar,
     * o Jsoup veria um parágrafo único de texto e a estrutura de lista se perderia.
     */
    private String unescapeIfNeeded(String html) {
        if (html.contains("&lt;") && !html.contains("<li")) {
            return Jsoup.parse(html).text();
        }
        return html;
    }

    /** Preserva a quebra entre blocos, que o {@code text()} do Jsoup descartaria. */
    private String toPlainText(Document document) {
        Document copy = document.clone();
        copy.outputSettings().prettyPrint(false);
        copy.select("br").after("\\n");
        copy.select("p, div, li, tr, h1, h2, h3, h4, h5, h6").after("\\n");
        copy.select("li").prepend("• ");
        String text = copy.text().replace("\\n", "\n");
        return text.replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /**
     * Percorre os elementos na ordem do documento, guardando qual título foi visto por
     * último; cada {@code <li>} é atribuído a esse contexto.
     */
    private void collectFromLists(Document document, List<String> required, List<String> nice) {
        Elements candidates = document.select("h1, h2, h3, h4, h5, h6, p, strong, b, li");
        List<String> current = null;

        for (Element element : candidates) {
            String text = element.ownText().isBlank() ? element.text() : element.ownText();
            text = text.trim();
            if (text.isEmpty()) {
                continue;
            }

            if ("li".equals(element.tagName())) {
                if (current != null && current.size() < MAX_ITEMS && isUsable(text)) {
                    current.add(Text.truncate(text, MAX_ITEM_LENGTH));
                }
                continue;
            }

            // Título só é título se for curto: um parágrafo inteiro citando "requisitos"
            // no meio da frase não abre seção.
            if (text.length() > 80) {
                continue;
            }
            if (NICE_HEADING.matcher(text).find()) {
                current = nice;
            } else if (REQUIRED_HEADING.matcher(text).find()) {
                current = required;
            } else if (STOP_HEADING.matcher(text).find()) {
                current = null;
            }
        }
    }

    /** Anúncio sem marcação: procura linhas com marcador abaixo de um título reconhecido. */
    private void collectFromLines(String plain, List<String> required, List<String> nice) {
        List<String> current = null;
        for (String rawLine : plain.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() <= 80) {
                if (NICE_HEADING.matcher(line).find()) {
                    current = nice;
                    continue;
                }
                if (REQUIRED_HEADING.matcher(line).find()) {
                    current = required;
                    continue;
                }
                if (STOP_HEADING.matcher(line).find()) {
                    current = null;
                    continue;
                }
            }
            if (current == null) {
                continue;
            }
            String item = line.replaceFirst("^[•▪◦*\\-–—·]\\s*", "").trim();
            boolean wasBullet = !item.equals(line);
            if (wasBullet && isUsable(item) && current.size() < MAX_ITEMS) {
                current.add(Text.truncate(item, MAX_ITEM_LENGTH));
            }
        }
    }

    private boolean isUsable(String text) {
        return text.length() >= MIN_ITEM_LENGTH && text.length() <= MAX_ITEM_LENGTH * 2;
    }

    /** Corta no fim de frase quando possível, para o card não terminar no meio de uma palavra. */
    private String shorten(String plain) {
        String flat = plain.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (flat.length() <= SHORT_DESCRIPTION_LENGTH) {
            return flat;
        }
        String window = flat.substring(0, SHORT_DESCRIPTION_LENGTH);
        int lastStop = Math.max(window.lastIndexOf(". "), window.lastIndexOf("! "));
        if (lastStop > SHORT_DESCRIPTION_LENGTH / 2) {
            return window.substring(0, lastStop + 1);
        }
        int lastSpace = window.lastIndexOf(' ');
        return (lastSpace > 0 ? window.substring(0, lastSpace) : window).trim() + "...";
    }
}
