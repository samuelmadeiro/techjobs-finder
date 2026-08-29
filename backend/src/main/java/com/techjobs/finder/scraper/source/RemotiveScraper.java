package com.techjobs.finder.scraper.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.exception.ScraperException;
import com.techjobs.finder.scraper.JobScraper;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.http.HttpFetcher;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Remotive expõe uma API pública e documentada para acesso automatizado
 * (https://remotive.com/api/remote-jobs). Nenhum HTML é raspado aqui.
 */
@Component
public class RemotiveScraper implements JobScraper {

    private static final Logger log = LoggerFactory.getLogger(RemotiveScraper.class);
    private static final String BASE_URL = "https://remotive.com";
    private static final DateTimeFormatter PUBLISHED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final HttpFetcher fetcher;
    private final ObjectMapper objectMapper;

    public RemotiveScraper(HttpFetcher fetcher, ObjectMapper objectMapper) {
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getSource() {
        return "remotive";
    }

    @Override
    public String getDisplayName() {
        return "Remotive";
    }

    @Override
    public String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    public List<RawJob> search(JobSearchFilter filter) {
        String query = filter.toQueryText();
        StringBuilder url = new StringBuilder(BASE_URL + "/api/remote-jobs?limit=100&category=software-dev");
        if (!query.isBlank()) {
            url.append("&search=").append(HttpFetcher.encode(query));
        }

        String body = fetcher.get(getSource(), url.toString());
        JsonNode root = parse(body);
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) {
            log.warn("Resposta da Remotive sem array 'jobs'; estrutura pode ter mudado");
            return List.of();
        }

        List<RawJob> result = new ArrayList<>();
        for (Iterator<JsonNode> it = jobs.elements(); it.hasNext(); ) {
            JsonNode node = it.next();
            try {
                result.add(toRawJob(node));
            } catch (RuntimeException e) {
                // Um item malformado não invalida a página inteira.
                log.debug("Item ignorado na Remotive: {}", e.getMessage());
            }
        }
        return result;
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
                .setExternalId(text(node, "id"))
                .setTitle(text(node, "title"))
                .setCompany(text(node, "company_name"))
                .setLocation(text(node, "candidate_required_location"))
                .setUrl(text(node, "url"))
                .setDescriptionHtml(text(node, "description"))
                .setSalaryRaw(text(node, "salary"))
                // Toda vaga da Remotive é remota por definição da plataforma.
                .setWorkModelHint("remote")
                .setLevelHint(text(node, "job_type"))
                .setPublishedAt(parseDate(text(node, "publication_date")));

        JsonNode tags = node.path("tags");
        if (tags.isArray()) {
            List<String> values = new ArrayList<>();
            tags.forEach(tag -> values.add(tag.asText(null)));
            job.addTags(values);
        }
        return job;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    /** A Remotive publica datas sem offset, em UTC. */
    private static java.time.Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), PUBLISHED_FORMAT).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
