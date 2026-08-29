package com.techjobs.finder.service;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.util.Text;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Converte o texto solto das fontes em valores canônicos: modalidade, nível,
 * tecnologias, localização e o fingerprint de deduplicação.
 */
@Component
public class JobNormalizer {

    private static final Pattern REMOTE = Pattern.compile(
            "(?<![a-z])(remoto|remote|home[- ]office|anywhere|work from home|100% remoto)(?![a-z])");
    private static final Pattern HYBRID = Pattern.compile(
            "(?<![a-z])(hibrido|híbrido|hybrid|semi[- ]?presencial)(?![a-z])");
    private static final Pattern ONSITE = Pattern.compile(
            "(?<![a-z])(presencial|on[- ]?site|no escritorio|in office)(?![a-z])");

    /**
     * Valor monetário plausível como salário: moeda seguida de número com pelo menos quatro
     * dígitos, ou com sufixo {@code k}/{@code mil}, ou acompanhado de periodicidade.
     * O {@code (?![mb])} descarta "$100M de investimento" e "$1B em ARR", que aparecem em
     * texto institucional e não têm nada a ver com remuneração.
     */
    private static final Pattern SALARY_AMOUNT = Pattern.compile(
            "(?i)(r\\$|us\\$|usd|eur|eur\\s|€|\\$)\\s?"
                    + "(\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d{2})?|\\d{4,}|\\d{1,3}\\s?(?:k|mil))(?![mb])"
                    + "(\\s*(?:-|a|to|ate|até|–)\\s*(?:r\\$|us\\$|usd|eur|€|\\$)?\\s?"
                    + "(?:\\d{1,3}(?:[.,]\\d{3})+|\\d{4,}|\\d{1,3}\\s?(?:k|mil)))?"
                    + "(\\s*(?:/|por|per)\\s*(?:ano|mes|mês|hora|year|month|hour|yr|hr))?");

    /** O valor só é aceito se aparecer perto de alguma destas palavras. */
    private static final Pattern SALARY_CONTEXT = Pattern.compile(
            "(?i)(salari|salary|remunera|compensation|pay range|base pay|wage|faixa|"
                    + "pacote|package|bolsa|hourly rate|annual rate|ctc)");

    /** Janela de texto ao redor da palavra de contexto onde o valor é procurado. */
    private static final int SALARY_WINDOW = 200;

    private final TechnologyCatalog catalog;
    private final ExperienceLevelDetector levelDetector;
    private final JobDescriptionParser descriptionParser;
    private final SalaryParser salaryParser;
    private final CountryCatalog countryCatalog;

    public JobNormalizer(TechnologyCatalog catalog,
                         ExperienceLevelDetector levelDetector,
                         JobDescriptionParser descriptionParser,
                         SalaryParser salaryParser,
                         CountryCatalog countryCatalog) {
        this.catalog = catalog;
        this.levelDetector = levelDetector;
        this.descriptionParser = descriptionParser;
        this.salaryParser = salaryParser;
        this.countryCatalog = countryCatalog;
    }

    /** Resultado da normalização de uma vaga crua. */
    public record Normalized(
            String title,
            String normalizedTitle,
            String company,
            String normalizedCompany,
            String location,
            String country,
            /* ISO-3166 alpha-2, ou ZZ quando a vaga não é de um país só. É o que a busca filtra. */
            String countryCode,
            WorkModel workModel,
            ExperienceLevel experienceLevel,
            String summary,
            String description,
            String shortDescription,
            List<String> requirements,
            List<String> niceToHave,
            String salary,
            SalaryParser.ParsedSalary structuredSalary,
            String benefits,
            Set<String> technologySlugs,
            Integer experienceYears,
            String fingerprint,
            RawJob raw) {
    }

    public Normalized normalize(RawJob raw, String source) {
        JobDescriptionParser.ParsedDescription parsed = descriptionParser.parse(raw.getDescriptionHtml());
        // O texto achatado continua sendo a base das heurísticas (nível, tecnologias,
        // salário); a versão com quebras de linha é o que o usuário lê na tela.
        String description = Text.stripHtml(raw.getDescriptionHtml());
        String title = Text.truncate(raw.getTitle().trim(), 500);
        String company = Text.blankToNull(raw.getCompany());
        String location = Text.truncate(Text.blankToNull(raw.getLocation()), 255);

        WorkModel workModel = detectWorkModel(raw, title, location, description);
        ExperienceLevel level = levelDetector.detect(title, raw.getLevelHint(), description, raw.getTags());
        Integer years = levelDetector.extractYearsOfExperience(description);
        Set<String> technologies = catalog.detect(title, description, raw.getTags());
        String salary = Text.truncate(detectSalary(raw, description), 255);

        return new Normalized(
                title,
                Text.normalize(title),
                company,
                Text.normalizeCompany(company),
                location,
                Text.truncate(detectCountry(location), 120),
                countryCatalog.classify(location, detectCountry(location)),
                workModel,
                level,
                Text.truncate(description, 2000),
                parsed.plainText(),
                Text.truncate(parsed.shortText(), 400),
                parsed.requirements(),
                parsed.niceToHave(),
                salary,
                salaryParser.parse(salary),
                Text.truncate(Text.blankToNull(raw.getBenefits()), 1000),
                technologies,
                years,
                fingerprint(company, title, location, raw.getUrl(), source),
                raw);
    }

    private WorkModel detectWorkModel(RawJob raw, String title, String location, String description) {
        WorkModel hinted = WorkModel.from(raw.getWorkModelHint());
        if (hinted != null && hinted != WorkModel.UNKNOWN) {
            return hinted;
        }
        String haystack = Text.normalize(String.join(" ", nz(title), nz(location),
                nz(String.join(" ", raw.getTags())), nz(Text.truncate(description, 1500))));
        if (haystack == null) {
            return WorkModel.UNKNOWN;
        }
        // Híbrido primeiro: textos híbridos quase sempre citam "remoto" também.
        if (HYBRID.matcher(haystack).find()) {
            return WorkModel.HYBRID;
        }
        if (REMOTE.matcher(haystack).find()) {
            return WorkModel.REMOTE;
        }
        if (ONSITE.matcher(haystack).find()) {
            return WorkModel.ONSITE;
        }
        return WorkModel.UNKNOWN;
    }

    /**
     * Usa o salário declarado pela fonte quando existe. Caso contrário procura no texto,
     * mas só perto de palavras que indiquem remuneração — número solto com cifrão costuma
     * ser rodada de investimento, faturamento ou preço de produto.
     */
    private String detectSalary(RawJob raw, String description) {
        String declared = Text.blankToNull(raw.getSalaryRaw());
        if (declared != null) {
            return declared;
        }
        if (description == null) {
            return null;
        }
        Matcher context = SALARY_CONTEXT.matcher(description);
        while (context.find()) {
            int from = Math.max(0, context.start() - SALARY_WINDOW);
            int to = Math.min(description.length(), context.end() + SALARY_WINDOW);
            Matcher amount = SALARY_AMOUNT.matcher(description.substring(from, to));
            if (amount.find()) {
                return amount.group().trim();
            }
        }
        return null;
    }

    /** Heurística simples: último segmento da localização costuma ser o país ou o estado. */
    private String detectCountry(String location) {
        if (location == null) {
            return null;
        }
        String[] parts = location.split("[,|/]");
        String last = parts[parts.length - 1].trim();
        return last.isEmpty() ? null : last;
    }

    /**
     * Identidade da vaga entre fontes distintas: empresa + título + localização.
     * A URL entra apenas quando a empresa é desconhecida, para não colidir vagas
     * diferentes sem empresa identificada.
     */
    public String fingerprint(String company, String title, String location, String url, String source) {
        String normalizedCompany = Text.normalizeCompany(company);
        String canonical;
        if (normalizedCompany != null && !normalizedCompany.isBlank()) {
            canonical = String.join("|",
                    normalizedCompany,
                    nz(Text.normalize(title)),
                    nz(Text.normalize(location)));
        } else {
            canonical = String.join("|", source, nz(Text.normalize(title)), nz(url).toLowerCase(Locale.ROOT));
        }
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
