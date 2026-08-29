package com.techjobs.finder.scraper.source;

import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.exception.ScraperException;
import com.techjobs.finder.scraper.JobScraper;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.http.HttpFetcher;
import com.techjobs.finder.util.Text;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * We Work Remotely publica feeds RSS por categoria, feitos para consumo automatizado.
 * Este scraper demonstra o caminho de parsing com Jsoup (XML/HTML) em vez de API JSON.
 *
 * <p>O título do RSS vem no formato {@code "Empresa: Cargo"}; a separação é feita aqui.
 */
@Component
public class WeWorkRemotelyScraper implements JobScraper {

    private static final Logger log = LoggerFactory.getLogger(WeWorkRemotelyScraper.class);
    private static final String BASE_URL = "https://weworkremotely.com";
    /** Todas as categorias de tecnologia do site; cada uma tem seu próprio feed RSS. */
    private static final List<String> FEEDS = List.of(
            "/categories/remote-programming-jobs.rss",
            "/categories/remote-back-end-programming-jobs.rss",
            "/categories/remote-front-end-programming-jobs.rss",
            "/categories/remote-full-stack-programming-jobs.rss",
            "/categories/remote-devops-sysadmin-jobs.rss",
            "/categories/remote-design-jobs.rss",
            "/categories/remote-product-jobs.rss",
            "/remote-jobs.rss");
    private static final DateTimeFormatter RSS_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final HttpFetcher fetcher;

    public WeWorkRemotelyScraper(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String getSource() {
        return "weworkremotely";
    }

    @Override
    public String getDisplayName() {
        return "We Work Remotely";
    }

    @Override
    public String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    public List<RawJob> search(JobSearchFilter filter) {
        List<String> terms = terms(filter);
        List<RawJob> result = new ArrayList<>();
        ScraperException lastError = null;

        for (String feed : FEEDS) {
            try {
                result.addAll(parseFeed(fetcher.get(getSource(), BASE_URL + feed), terms));
            } catch (ScraperException e) {
                // Um feed fora do ar não invalida os demais desta mesma fonte.
                log.warn("Feed {} indisponível: {}", feed, e.getMessage());
                lastError = e;
            }
        }
        if (result.isEmpty() && lastError != null) {
            throw lastError;
        }
        return result;
    }

    private List<RawJob> parseFeed(String xml, List<String> terms) {
        Document document = Jsoup.parse(xml, BASE_URL, Parser.xmlParser());
        List<RawJob> jobs = new ArrayList<>();

        for (Element item : document.select("item")) {
            try {
                String rawTitle = item.selectFirst("title") == null ? null : item.selectFirst("title").text();
                if (rawTitle == null || rawTitle.isBlank()) {
                    continue;
                }
                String company = null;
                String title = rawTitle.trim();
                int separator = rawTitle.indexOf(':');
                if (separator > 0) {
                    company = rawTitle.substring(0, separator).trim();
                    title = rawTitle.substring(separator + 1).trim();
                }

                RawJob job = new RawJob()
                        .setTitle(title)
                        .setCompany(company)
                        .setUrl(childText(item, "link"))
                        .setExternalId(childText(item, "guid"))
                        .setDescriptionHtml(childText(item, "description"))
                        .setLocation(childText(item, "region"))
                        .setWorkModelHint("remote")
                        .setPublishedAt(parseDate(childText(item, "pubDate")));

                String category = childText(item, "category");
                if (category != null) {
                    job.addTags(List.of(category));
                }
                if (job.isUsable() && matches(job, terms)) {
                    jobs.add(job);
                }
            } catch (RuntimeException e) {
                // HTML/XML mudou ou campo ausente: descarta o item, mantém o resto.
                log.debug("Item ignorado no feed WWR: {}", e.getMessage());
            }
        }
        if (jobs.isEmpty()) {
            log.debug("Feed WWR sem itens compatíveis com o filtro");
        }
        return jobs;
    }

    private static String childText(Element item, String tag) {
        Element child = item.selectFirst(tag);
        return child == null ? null : Text.blankToNull(child.text());
    }

    private static List<String> terms(JobSearchFilter filter) {
        String query = Text.normalize(filter.toQueryText());
        return query == null || query.isBlank() ? List.of() : List.of(query.split(" "));
    }

    private static boolean matches(RawJob job, List<String> terms) {
        if (terms.isEmpty()) {
            return true;
        }
        String normalized = Text.normalize(job.getTitle() + " " + job.getDescriptionHtml());
        return normalized != null && terms.stream().anyMatch(normalized::contains);
    }

    private static Instant parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value.trim(), RSS_DATE).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
