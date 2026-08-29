package com.techjobs.finder.scraper.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.config.ScraperProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.exception.ScraperException;
import com.techjobs.finder.scraper.JobScraper;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.http.HttpFetcher;
import com.techjobs.finder.service.CountryCatalog;
import com.techjobs.finder.util.Slugs;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Jobicy publica uma API REST aberta e documentada (https://jobicy.com/jobs-rss-feed).
 * Traz {@code jobLevel}, o que dá um sinal forte de senioridade direto da fonte.
 *
 * <p>A API filtra por tag, então a varredura profunda percorre as linguagens do catálogo —
 * é assim que se ganha volume sem depender do que o usuário pesquisou.
 *
 * <p><strong>Única fonte com filtro de país de verdade.</strong> O parâmetro {@code geo} foi
 * testado contra a API antes de ser usado aqui: {@code brazil}, {@code usa}, {@code canada},
 * {@code portugal}, {@code uk}, {@code germany}, {@code spain}, {@code france} e
 * {@code australia} respondem; {@code united-kingdom} e {@code india} não. O slug de cada
 * país mora no {@code CountryCatalog} — país sem slug simplesmente não manda {@code geo}, e a
 * seleção acontece depois, no banco, pelo {@code country_code} da vaga.
 */
@Component
public class JobicyScraper implements JobScraper {

    private static final Logger log = LoggerFactory.getLogger(JobicyScraper.class);
    private static final String BASE_URL = "https://jobicy.com";
    private static final int COUNT_PER_REQUEST = 50;

    /** Tags varridas no harvest, escolhidas por cobrirem o grosso das vagas de TI. */
    private static final List<String> HARVEST_TAGS = List.of(
            "java", "python", "javascript", "typescript", "golang", "php", "ruby", "kotlin",
            "rust", "react", "angular", "vue", "node", "spring", "django", "laravel",
            "devops", "docker", "kubernetes", "aws", "sql", "backend", "frontend", "fullstack",
            "mobile", "android", "ios", "data", "qa", "engineer", "developer");

    private final HttpFetcher fetcher;
    private final ObjectMapper objectMapper;
    private final ScraperProperties properties;
    private final CountryCatalog countries;

    public JobicyScraper(HttpFetcher fetcher, ObjectMapper objectMapper,
                         ScraperProperties properties, CountryCatalog countries) {
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.countries = countries;
    }

    @Override
    public String getSource() {
        return "jobicy";
    }

    @Override
    public String getDisplayName() {
        return "Jobicy";
    }

    @Override
    public String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    public List<RawJob> search(JobSearchFilter filter) {
        List<String> tags = filter.allTechnologySlugs().isEmpty()
                ? List.of("")
                : filter.allTechnologySlugs().stream().map(JobicyScraper::toJobicyTag).toList();

        String geo = geoFor(filter);
        Map<String, RawJob> byUrl = new LinkedHashMap<>();
        for (String tag : tags) {
            fetchTag(tag, geo).forEach(job -> byUrl.putIfAbsent(job.getUrl(), job));
        }
        return List.copyOf(byUrl.values());
    }

    /**
     * Slug de país aceito pela API, ou {@code null} quando não há um.
     *
     * <p>Sem correspondência a busca sai sem {@code geo}: pedir um valor que a API não
     * conhece devolve resposta vazia, e uma fonte que "não suporta este país" precisa
     * continuar contribuindo com as vagas globais em vez de sumir da coleta.
     */
    private String geoFor(JobSearchFilter filter) {
        return countries.find(filter.country())
                .map(CountryCatalog.Country::jobicyGeo)
                .orElse(null);
    }

    @Override
    public List<RawJob> harvest() {
        Map<String, RawJob> byUrl = new LinkedHashMap<>();
        int limit = properties.getHarvest().getMaxResultsPerSource();

        // Feed geral primeiro, depois cada tag, até bater o teto configurado.
        for (String tag : prependEmpty(HARVEST_TAGS)) {
            if (byUrl.size() >= limit) {
                break;
            }
            try {
                // A varredura profunda é sem país: ela alimenta o acervo inteiro, e é o
                // country_code de cada vaga que decide em qual busca ela aparece.
                fetchTag(tag, null).forEach(job -> byUrl.putIfAbsent(job.getUrl(), job));
            } catch (ScraperException e) {
                // Uma tag que falha não invalida a varredura inteira desta fonte.
                log.warn("Jobicy falhou na tag '{}': {}", tag, e.getMessage());
            }
        }
        return List.copyOf(byUrl.values());
    }

    private static List<String> prependEmpty(List<String> tags) {
        List<String> all = new ArrayList<>();
        all.add("");
        all.addAll(tags);
        return all;
    }

    /** O slug interno vira a tag esperada pela API ({@code nodejs} -> {@code node}). */
    private static String toJobicyTag(String slug) {
        return switch (slug) {
            case "go" -> "golang";
            case "nodejs" -> "node";
            case "vuejs" -> "vue";
            case "spring-boot" -> "spring";
            case "csharp" -> "c#";
            default -> Slugs.toDisplay(slug) == null ? slug : slug.replace('-', ' ');
        };
    }

    private List<RawJob> fetchTag(String tag, String geo) {
        StringBuilder url = new StringBuilder(BASE_URL + "/api/v2/remote-jobs?count=" + COUNT_PER_REQUEST);
        if (tag != null && !tag.isBlank()) {
            url.append("&tag=").append(HttpFetcher.encode(tag));
        }
        if (geo != null && !geo.isBlank()) {
            url.append("&geo=").append(HttpFetcher.encode(geo));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(fetcher.get(getSource(), url.toString()));
        } catch (ScraperException e) {
            throw e;
        } catch (Exception e) {
            throw new ScraperException(getSource(), "Resposta não é um JSON válido", e);
        }

        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) {
            log.warn("Resposta da Jobicy sem array 'jobs'; estrutura pode ter mudado");
            return List.of();
        }

        List<RawJob> result = new ArrayList<>();
        for (JsonNode node : jobs) {
            try {
                RawJob job = toRawJob(node);
                if (job.isUsable()) {
                    result.add(job);
                }
            } catch (RuntimeException e) {
                log.debug("Item ignorado na Jobicy: {}", e.getMessage());
            }
        }
        return result;
    }

    private RawJob toRawJob(JsonNode node) {
        RawJob job = new RawJob()
                .setExternalId(text(node, "id"))
                .setTitle(text(node, "jobTitle"))
                .setCompany(text(node, "companyName"))
                .setLocation(text(node, "jobGeo"))
                .setUrl(text(node, "url"))
                // Resumo antes da descrição: ele concentra a stack e ajuda na detecção.
                .setDescriptionHtml(join(text(node, "jobExcerpt"), text(node, "jobDescription")))
                // jobLevel é o campo de senioridade da própria fonte: sinal forte para o detector.
                .setLevelHint(text(node, "jobLevel"))
                .setWorkModelHint("remote")
                .setSalaryRaw(salary(node))
                .setPublishedAt(parseDate(text(node, "pubDate")));

        List<String> tags = new ArrayList<>();
        node.path("jobIndustry").forEach(value -> tags.add(value.asText(null)));
        node.path("jobType").forEach(value -> tags.add(value.asText(null)));
        job.addTags(tags);
        return job;
    }

    private static String salary(JsonNode node) {
        double min = node.path("annualSalaryMin").asDouble(0);
        double max = node.path("annualSalaryMax").asDouble(0);
        String currency = node.path("salaryCurrency").asText("USD");
        if (min <= 0 && max <= 0) {
            return null;
        }
        if (min > 0 && max > 0) {
            return "%s %,.0f - %,.0f / ano".formatted(currency, min, max);
        }
        return "%s %,.0f / ano".formatted(currency, Math.max(min, max));
    }

    private static Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return OffsetDateTime.parse(trimmed.replace(" ", "T") + "Z").toInstant();
        } catch (RuntimeException ignored) {
            try {
                return java.time.LocalDateTime.parse(trimmed.replace(" ", "T"))
                        .toInstant(java.time.ZoneOffset.UTC);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "\n" + second;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }
}
