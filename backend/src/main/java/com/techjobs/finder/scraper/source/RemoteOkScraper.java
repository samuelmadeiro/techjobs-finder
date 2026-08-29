package com.techjobs.finder.scraper.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.exception.ScraperException;
import com.techjobs.finder.scraper.JobScraper;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.http.HttpFetcher;
import com.techjobs.finder.util.Text;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RemoteOK publica um feed JSON aberto em https://remoteok.com/api.
 * O uso exige atribuição e link de volta para a vaga original — o frontend
 * sempre exibe a fonte e aponta o botão "Ver vaga" para a URL original.
 *
 * <p>O primeiro elemento do array é um aviso legal, não uma vaga, e é descartado.
 */
@Component
public class RemoteOkScraper implements JobScraper {

    private static final Logger log = LoggerFactory.getLogger(RemoteOkScraper.class);
    private static final String BASE_URL = "https://remoteok.com";

    private final HttpFetcher fetcher;
    private final ObjectMapper objectMapper;

    public RemoteOkScraper(HttpFetcher fetcher, ObjectMapper objectMapper) {
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getSource() {
        return "remoteok";
    }

    @Override
    public String getDisplayName() {
        return "RemoteOK";
    }

    @Override
    public String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    public List<RawJob> search(JobSearchFilter filter) {
        String body = fetcher.get(getSource(), BASE_URL + "/api");
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new ScraperException(getSource(), "Resposta não é um JSON válido", e);
        }
        if (!root.isArray()) {
            log.warn("Resposta da RemoteOK não é um array; estrutura pode ter mudado");
            return List.of();
        }

        List<String> terms = terms(filter);
        List<RawJob> result = new ArrayList<>();
        for (JsonNode node : root) {
            if (node.has("legal")) {
                continue;
            }
            try {
                RawJob job = toRawJob(node);
                if (job.isUsable() && matches(job, terms)) {
                    result.add(job);
                }
            } catch (RuntimeException e) {
                log.debug("Item ignorado na RemoteOK: {}", e.getMessage());
            }
        }
        return result;
    }

    private static List<String> terms(JobSearchFilter filter) {
        String query = Text.normalize(filter.toQueryText());
        return query == null || query.isBlank() ? List.of() : List.of(query.split(" "));
    }

    private static boolean matches(RawJob job, List<String> terms) {
        if (terms.isEmpty()) {
            return true;
        }
        String normalized = Text.normalize(
                job.getTitle() + " " + String.join(" ", job.getTags()) + " " + job.getCompany());
        return normalized != null && terms.stream().anyMatch(normalized::contains);
    }

    private RawJob toRawJob(JsonNode node) {
        String slug = text(node, "slug");
        String url = text(node, "url");
        if (url == null && slug != null) {
            url = BASE_URL + "/remote-jobs/" + slug;
        }

        RawJob job = new RawJob()
                .setExternalId(text(node, "id"))
                .setTitle(text(node, "position"))
                .setCompany(text(node, "company"))
                .setLocation(text(node, "location"))
                .setUrl(url)
                .setDescriptionHtml(text(node, "description"))
                .setWorkModelHint("remote")
                .setSalaryRaw(salary(node))
                .setPublishedAt(epoch(node));

        List<String> tags = new ArrayList<>();
        node.path("tags").forEach(tag -> tags.add(tag.asText(null)));
        job.addTags(tags);
        return job;
    }

    private static String salary(JsonNode node) {
        long min = node.path("salary_min").asLong(0);
        long max = node.path("salary_max").asLong(0);
        if (min <= 0 && max <= 0) {
            return null;
        }
        if (min > 0 && max > 0) {
            return "USD %,d - %,d / ano".formatted(min, max);
        }
        return "USD %,d / ano".formatted(Math.max(min, max));
    }

    private static Instant epoch(JsonNode node) {
        long epoch = node.path("epoch").asLong(0);
        if (epoch > 0) {
            return Instant.ofEpochSecond(epoch);
        }
        String date = text(node, "date");
        if (date == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(date).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }
}
