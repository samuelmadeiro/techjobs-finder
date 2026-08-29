package com.techjobs.finder.scraper.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.http.HttpFetcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class WeWorkRemotelyScraperTest {

    private final HttpFetcher fetcher = mock(HttpFetcher.class);
    private final WeWorkRemotelyScraper scraper = new WeWorkRemotelyScraper(fetcher);

    private String fixture() throws IOException {
        try (var input = new ClassPathResource("fixtures/weworkremotely-feed.rss").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("separa empresa e cargo do título do RSS")
    void splitsCompanyAndTitle() throws IOException {
        when(fetcher.get(eq("weworkremotely"), anyString())).thenReturn(fixture());

        List<RawJob> jobs = scraper.search(JobSearchFilter.empty());

        assertThat(jobs).isNotEmpty();
        RawJob first = jobs.get(0);
        assertThat(first.getCompany()).isEqualTo("Empresa XYZ");
        assertThat(first.getTitle()).isEqualTo("Junior Java Developer");
        assertThat(first.getUrl()).isEqualTo(
                "https://weworkremotely.com/remote-jobs/empresa-xyz-junior-java-developer");
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(first.getLocation()).isEqualTo("Anywhere (100% Remote)");
    }

    @Test
    @DisplayName("item sem link é descartado sem interromper o feed")
    void skipsBrokenItems() throws IOException {
        when(fetcher.get(eq("weworkremotely"), anyString())).thenReturn(fixture());

        List<RawJob> jobs = scraper.search(JobSearchFilter.empty());

        assertThat(jobs).allMatch(RawJob::isUsable);
        assertThat(jobs).noneMatch(job -> "Item quebrado sem link".equals(job.getTitle()));
    }

    @Test
    @DisplayName("filtra pelo termo pedido")
    void filtersByTerm() throws IOException {
        when(fetcher.get(eq("weworkremotely"), anyString())).thenReturn(fixture());
        JobSearchFilter filter = new JobSearchFilter(List.of("rust"), List.of(),
                null, null, null, null, null, List.of());

        List<RawJob> jobs = scraper.search(filter);

        assertThat(jobs).isNotEmpty();
        assertThat(jobs).allMatch(job -> job.getTitle().toLowerCase().contains("rust")
                || String.valueOf(job.getDescriptionHtml()).toLowerCase().contains("rust"));
    }

    @Test
    @DisplayName("XML malformado não lança: apenas não produz vagas")
    void toleratesMalformedXml() {
        when(fetcher.get(eq("weworkremotely"), anyString())).thenReturn("<rss><channel>");

        assertThat(scraper.search(JobSearchFilter.empty())).isEmpty();
    }
}
