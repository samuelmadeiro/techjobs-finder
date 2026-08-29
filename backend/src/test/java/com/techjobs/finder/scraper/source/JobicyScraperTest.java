package com.techjobs.finder.scraper.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.config.ScraperProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.service.CountryCatalog;
import com.techjobs.finder.scraper.http.HttpFetcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class JobicyScraperTest {

    private final HttpFetcher fetcher = mock(HttpFetcher.class);
    private final ScraperProperties properties = new ScraperProperties();
    private final JobicyScraper scraper = new JobicyScraper(fetcher, new ObjectMapper(), properties, new CountryCatalog());

    private String fixture() throws IOException {
        try (var input = new ClassPathResource("fixtures/jobicy-response.json").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("converte a resposta em vagas cruas com dica de senioridade")
    void parsesResponse() throws IOException {
        when(fetcher.get(eq("jobicy"), anyString())).thenReturn(fixture());

        List<RawJob> jobs = scraper.search(JobSearchFilter.empty());

        assertThat(jobs).hasSize(2);
        RawJob first = jobs.get(0);
        assertThat(first.getTitle()).isEqualTo("Junior Java Developer");
        assertThat(first.getCompany()).isEqualTo("Empresa XYZ");
        assertThat(first.getLevelHint()).isEqualTo("Junior");
        assertThat(first.getSalaryRaw()).contains("USD");
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(jobs.get(1).getSalaryRaw()).isNull();
    }

    @Test
    @DisplayName("vagas repetidas entre tags aparecem uma vez só")
    void deduplicatesAcrossTags() throws IOException {
        when(fetcher.get(eq("jobicy"), anyString())).thenReturn(fixture());
        JobSearchFilter filter = new JobSearchFilter(List.of("java", "python"), List.of(),
                null, null, null, null, null, List.of());

        assertThat(scraper.search(filter)).hasSize(2);
    }

    @Test
    @DisplayName("harvest varre várias tags para ganhar volume")
    void harvestSweepsTags() throws IOException {
        when(fetcher.get(eq("jobicy"), anyString())).thenReturn(fixture());

        scraper.harvest();

        // Feed geral mais uma requisição por tag do catálogo de varredura.
        verify(fetcher, atLeast(10)).get(eq("jobicy"), anyString());
    }

    @Test
    @DisplayName("teto de resultados do harvest é respeitado")
    void harvestRespectsLimit() throws IOException {
        properties.getHarvest().setMaxResultsPerSource(1);
        when(fetcher.get(eq("jobicy"), anyString())).thenReturn(fixture());

        assertThat(scraper.harvest()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("estrutura inesperada devolve lista vazia")
    void toleratesStructureChange() {
        when(fetcher.get(eq("jobicy"), anyString())).thenReturn("{\"unexpected\":true}");

        assertThat(scraper.search(JobSearchFilter.empty())).isEmpty();
    }
}
