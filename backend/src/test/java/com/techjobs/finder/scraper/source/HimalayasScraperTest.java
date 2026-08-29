package com.techjobs.finder.scraper.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.config.ScraperProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.http.HttpFetcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class HimalayasScraperTest {

    private final HttpFetcher fetcher = mock(HttpFetcher.class);
    private final ScraperProperties properties = new ScraperProperties();
    private final HimalayasScraper scraper = new HimalayasScraper(fetcher, new ObjectMapper(), properties);

    private String fixture() throws IOException {
        try (var input = new ClassPathResource("fixtures/himalayas-response.json").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("converte a resposta paginada em vagas cruas")
    void parsesResponse() throws IOException {
        when(fetcher.get(eq("himalayas"), anyString())).thenReturn(fixture());

        List<RawJob> jobs = scraper.search(JobSearchFilter.empty());

        assertThat(jobs).isNotEmpty();
        RawJob first = jobs.get(0);
        assertThat(first.getTitle()).isEqualTo("Backend Engineer");
        assertThat(first.getLocation()).isEqualTo("Brazil, LATAM");
        assertThat(first.getLevelHint()).isEqualTo("Mid-level");
        assertThat(first.getSalaryRaw()).contains("60,000");
        assertThat(first.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("harvest pagina usando offset até o limite configurado")
    void harvestPaginates() throws IOException {
        properties.getHarvest().setMaxPages(3);
        when(fetcher.get(eq("himalayas"), anyString())).thenReturn(fixture());

        scraper.harvest();

        verify(fetcher).get(eq("himalayas"), contains("offset=0"));
        verify(fetcher).get(eq("himalayas"), contains("offset=50"));
        verify(fetcher).get(eq("himalayas"), contains("offset=100"));
    }

    @Test
    @DisplayName("página vazia interrompe a paginação")
    void stopsOnEmptyPage() {
        when(fetcher.get(eq("himalayas"), anyString())).thenReturn("{\"jobs\":[]}");
        properties.getHarvest().setMaxPages(10);

        assertThat(scraper.harvest()).isEmpty();
        verify(fetcher).get(eq("himalayas"), anyString());
    }

    @Test
    @DisplayName("falha no meio da paginação entrega o parcial em vez de perder tudo")
    void keepsPartialResultOnFailure() throws IOException {
        properties.getHarvest().setMaxPages(5);
        when(fetcher.get(eq("himalayas"), contains("offset=0"))).thenReturn(fixture());
        when(fetcher.get(eq("himalayas"), contains("offset=50")))
                .thenThrow(new com.techjobs.finder.exception.ScraperException("himalayas", "HTTP 429"));

        assertThat(scraper.harvest()).hasSize(2);
    }

    @Test
    @DisplayName("falha já na primeira página propaga o erro")
    void propagatesFailureOnFirstPage() {
        when(fetcher.get(eq("himalayas"), anyString()))
                .thenThrow(new com.techjobs.finder.exception.ScraperException("himalayas", "HTTP 429"));

        org.assertj.core.api.Assertions.assertThatThrownBy(scraper::harvest)
                .isInstanceOf(com.techjobs.finder.exception.ScraperException.class);
    }

    @Test
    @DisplayName("filtra pelo termo pedido na busca comum")
    void filtersByTerm() throws IOException {
        when(fetcher.get(eq("himalayas"), anyString())).thenReturn(fixture());
        JobSearchFilter filter = new JobSearchFilter(List.of("python"), List.of(),
                null, null, null, null, null, List.of());

        List<RawJob> jobs = scraper.search(filter);

        assertThat(jobs).isNotEmpty();
        assertThat(jobs).allMatch(job -> job.getTitle().contains("Data")
                || String.valueOf(job.getDescriptionHtml()).toLowerCase().contains("python")
                || String.join(" ", job.getTags()).toLowerCase().contains("data"));
    }
}
