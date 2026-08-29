package com.techjobs.finder.scraper.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.config.ScraperProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.exception.ScraperException;
import com.techjobs.finder.scraper.JobScraper;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.http.HttpFetcher;
import com.techjobs.finder.util.Text;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Himalayas expõe um endpoint JSON público e paginado (https://himalayas.app/jobs/api).
 * É a fonte com maior volume do conjunto e traz {@code seniority}, aproveitado como
 * dica de nível.
 */
@Component
public class HimalayasScraper implements JobScraper {

    private static final Logger log = LoggerFactory.getLogger(HimalayasScraper.class);
    private static final String BASE_URL = "https://himalayas.app";
    private static final int PAGE_SIZE = 50;
    /** Páginas percorridas na busca comum; o harvest usa o limite configurado. */
    private static final int SEARCH_PAGES = 2;

    private final HttpFetcher fetcher;
    private final ObjectMapper objectMapper;
    private final ScraperProperties properties;

    public HimalayasScraper(HttpFetcher fetcher, ObjectMapper objectMapper, ScraperProperties properties) {
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String getSource() {
        return "himalayas";
    }

    @Override
    public String getDisplayName() {
        return "Himalayas";
    }

    @Override
    public String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    public List<RawJob> search(JobSearchFilter filter) {
        List<String> terms = terms(filter);
        return fetchPages(SEARCH_PAGES, Integer.MAX_VALUE).stream()
                .filter(job -> matches(job, terms))
                .toList();
    }

    @Override
    public List<RawJob> harvest() {
        return fetchPages(properties.getHarvest().getMaxPages(),
                properties.getHarvest().getMaxResultsPerSource());
    }

    /** Percorre páginas até acabar o conteúdo, bater o teto de páginas ou o de resultados. */
    private List<RawJob> fetchPages(int maxPages, int maxResults) {
        List<RawJob> result = new ArrayList<>();
        for (int page = 0; page < maxPages && result.size() < maxResults; page++) {
            int offset = page * PAGE_SIZE;
            JsonNode jobs;
            try {
                jobs = fetchPage(offset).path("jobs");
            } catch (ScraperException e) {
                // Falha no meio da paginação: entrega o parcial em vez de perder tudo.
                if (result.isEmpty()) {
                    throw e;
                }
                log.warn("Himalayas interrompida no offset {} ({}); mantendo {} vaga(s)",
                        offset, e.getMessage(), result.size());
                break;
            }
            if (!jobs.isArray() || jobs.isEmpty()) {
                break;
            }
            for (JsonNode node : jobs) {
                try {
                    RawJob job = toRawJob(node);
                    if (job.isUsable()) {
                        result.add(job);
                    }
                } catch (RuntimeException e) {
                    log.debug("Item ignorado na Himalayas: {}", e.getMessage());
                }
            }
        }
        return result;
    }

    private JsonNode fetchPage(int offset) {
        String url = BASE_URL + "/jobs/api?limit=" + PAGE_SIZE + "&offset=" + offset;
        try {
            return objectMapper.readTree(fetcher.get(getSource(), url));
        } catch (ScraperException e) {
            throw e;
        } catch (Exception e) {
            throw new ScraperException(getSource(), "Resposta não é um JSON válido", e);
        }
    }

    private RawJob toRawJob(JsonNode node) {
        String url = firstNonBlank(text(node, "applicationLink"), text(node, "guid"));

        RawJob job = new RawJob()
                .setExternalId(text(node, "guid"))
                .setTitle(text(node, "title"))
                .setCompany(text(node, "companyName"))
                .setLocation(joinArray(node.path("locationRestrictions")))
                .setUrl(url)
                // Descrição e resumo somados: o resumo costuma listar a stack que a
                // descrição longa só menciona no meio do texto.
                .setDescriptionHtml(join(text(node, "excerpt"), text(node, "description")))
                .setLevelHint(joinArray(node.path("seniority")))
                .setWorkModelHint("remote")
                .setSalaryRaw(salary(node))
                .setPublishedAt(epochSeconds(node.path("pubDate").asLong(0)));

        List<String> tags = new ArrayList<>();
        node.path("categories").forEach(value -> tags.add(value.asText(null)));
        node.path("seniority").forEach(value -> tags.add(value.asText(null)));
        job.addTags(tags);
        return job;
    }

    private static String salary(JsonNode node) {
        long min = node.path("minSalary").asLong(0);
        long max = node.path("maxSalary").asLong(0);
        if (min <= 0 && max <= 0) {
            return null;
        }
        // Locale.ROOT explícito: o valor é em dólar e o separador de milhar precisa ser
        // vírgula em qualquer máquina. Sem isso, o texto muda com o locale do servidor —
        // "60,000" em um, "60.000" em outro — e o SalaryParser lê números diferentes.
        if (min > 0 && max > 0) {
            return String.format(Locale.ROOT, "USD %,d - %,d / ano", min, max);
        }
        return String.format(Locale.ROOT, "USD %,d / ano", Math.max(min, max));
    }

    private static String joinArray(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        array.forEach(item -> {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        });
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private static Instant epochSeconds(long value) {
        if (value <= 0) {
            return null;
        }
        // A fonte já usou milissegundos em alguns registros; normaliza os dois formatos.
        return value > 100_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
    }

    private static List<String> terms(JobSearchFilter filter) {
        String query = Text.normalize(filter.toQueryText());
        return query == null || query.isBlank() ? List.of() : List.of(query.split(" "));
    }

    private static boolean matches(RawJob job, List<String> terms) {
        if (terms.isEmpty()) {
            return true;
        }
        String normalized = Text.normalize(job.getTitle() + " " + String.join(" ", job.getTags())
                + " " + Text.truncate(String.valueOf(job.getDescriptionHtml()), 4000));
        return normalized != null && terms.stream().anyMatch(normalized::contains);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
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
