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
 * Arbeitnow oferece um job board API público e gratuito
 * (https://www.arbeitnow.com/api/job-board-api). A API não aceita termo de busca,
 * então a filtragem por palavra-chave é feita aqui, após a coleta paginada.
 */
@Component
public class ArbeitnowScraper implements JobScraper {

    private static final Logger log = LoggerFactory.getLogger(ArbeitnowScraper.class);
    private static final String BASE_URL = "https://www.arbeitnow.com";
    /** Páginas lidas na busca do usuário; o harvest usa o limite configurado. */
    private static final int SEARCH_PAGES = 3;

    private final HttpFetcher fetcher;
    private final ObjectMapper objectMapper;
    private final ScraperProperties properties;

    public ArbeitnowScraper(HttpFetcher fetcher, ObjectMapper objectMapper, ScraperProperties properties) {
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String getSource() {
        return "arbeitnow";
    }

    @Override
    public String getDisplayName() {
        return "Arbeitnow";
    }

    @Override
    public String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    public List<RawJob> search(JobSearchFilter filter) {
        return fetchPages(SEARCH_PAGES, Integer.MAX_VALUE, queryTerms(filter));
    }

    @Override
    public List<RawJob> harvest() {
        return fetchPages(properties.getHarvest().getMaxPages(),
                properties.getHarvest().getMaxResultsPerSource(), List.of());
    }

    private List<RawJob> fetchPages(int maxPages, int maxResults, List<String> terms) {
        List<RawJob> result = new ArrayList<>();

        for (int page = 1; page <= maxPages && result.size() < maxResults; page++) {
            JsonNode data;
            try {
                String body = fetcher.get(getSource(), BASE_URL + "/api/job-board-api?page=" + page);
                data = parse(body).path("data");
            } catch (ScraperException e) {
                // Falha no meio da paginação (429, timeout): fica com o que já veio.
                // Descartar páginas boas por causa da última seria pior para o usuário.
                if (result.isEmpty()) {
                    throw e;
                }
                log.warn("Arbeitnow interrompida na página {} ({}); mantendo {} vaga(s) já coletada(s)",
                        page, e.getMessage(), result.size());
                break;
            }
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            for (JsonNode node : data) {
                try {
                    RawJob job = toRawJob(node);
                    if (matches(job, terms)) {
                        result.add(job);
                    }
                } catch (RuntimeException e) {
                    log.debug("Item ignorado na Arbeitnow: {}", e.getMessage());
                }
            }
        }
        return result;
    }

    /** Sem termos o filtro é aberto; com termos, exige que ao menos um apareça no texto. */
    private static List<String> queryTerms(JobSearchFilter filter) {
        String query = Text.normalize(filter.toQueryText());
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return List.of(query.split(" "));
    }

    private static boolean matches(RawJob job, List<String> terms) {
        if (terms.isEmpty()) {
            return true;
        }
        String haystack = (job.getTitle() + " " + String.join(" ", job.getTags()) + " "
                + Text.truncate(String.valueOf(job.getDescriptionHtml()), 4000))
                .toLowerCase(Locale.ROOT);
        String normalized = Text.normalize(haystack);
        return normalized != null && terms.stream().anyMatch(normalized::contains);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new ScraperException(getSource(), "Resposta não é um JSON válido", e);
        }
    }

    private RawJob toRawJob(JsonNode node) {
        RawJob job = new RawJob()
                .setExternalId(text(node, "slug"))
                .setTitle(text(node, "title"))
                .setCompany(text(node, "company_name"))
                .setLocation(text(node, "location"))
                .setUrl(text(node, "url"))
                .setDescriptionHtml(text(node, "description"))
                .setWorkModelHint(node.path("remote").asBoolean(false) ? "remote" : null)
                .setPublishedAt(epochSeconds(node.path("created_at").asLong(0)));

        List<String> tags = new ArrayList<>();
        node.path("tags").forEach(tag -> tags.add(tag.asText(null)));
        node.path("job_types").forEach(type -> tags.add(type.asText(null)));
        job.addTags(tags);
        return job;
    }

    private static Instant epochSeconds(long value) {
        return value <= 0 ? null : Instant.ofEpochSecond(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }
}
