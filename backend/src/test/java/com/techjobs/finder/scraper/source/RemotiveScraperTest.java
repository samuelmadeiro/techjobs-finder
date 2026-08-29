package com.techjobs.finder.scraper.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.exception.ScraperException;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.http.HttpFetcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

/** Testes de parsing com HTML/JSON fixo: nenhuma requisição real é feita. */
class RemotiveScraperTest {

    private final HttpFetcher fetcher = mock(HttpFetcher.class);
    private final RemotiveScraper scraper = new RemotiveScraper(fetcher, new ObjectMapper());

    private String fixture(String name) throws IOException {
        try (var input = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("converte a resposta da API em vagas cruas")
    void parsesResponse() throws IOException {
        when(fetcher.get(eq("remotive"), anyString())).thenReturn(fixture("remotive-response.json"));

        List<RawJob> jobs = scraper.search(JobSearchFilter.empty());

        assertThat(jobs).hasSize(2);
        RawJob first = jobs.get(0);
        assertThat(first.getTitle()).isEqualTo("Junior Java Developer");
        assertThat(first.getCompany()).isEqualTo("Empresa XYZ");
        assertThat(first.getUrl()).startsWith("https://remotive.com/remote-jobs/");
        assertThat(first.getTags()).contains("java", "spring boot", "postgresql");
        assertThat(first.getWorkModelHint()).isEqualTo("remote");
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(first.getSalaryRaw()).isEqualTo("USD 40.000 - 55.000");
    }

    @Test
    @DisplayName("o termo de busca vai codificado na URL")
    void sendsSearchTerm() throws IOException {
        when(fetcher.get(eq("remotive"), anyString())).thenReturn(fixture("remotive-response.json"));
        JobSearchFilter filter = new JobSearchFilter(List.of("java"), List.of("spring-boot"),
                null, null, null, null, null, List.of());

        scraper.search(filter);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(fetcher).get(eq("remotive"), url.capture());
        assertThat(url.getValue()).contains("search=Java+Spring+Boot");
    }

    @Test
    @DisplayName("resposta sem o array esperado devolve lista vazia em vez de quebrar")
    void toleratesStructureChange() {
        when(fetcher.get(eq("remotive"), anyString())).thenReturn("{\"unexpected\":true}");

        assertThat(scraper.search(JobSearchFilter.empty())).isEmpty();
    }

    @Test
    @DisplayName("JSON inválido vira ScraperException, isolada pelo orquestrador")
    void invalidJsonFails() {
        when(fetcher.get(eq("remotive"), anyString())).thenReturn("<html>erro</html>");

        assertThatThrownBy(() -> scraper.search(JobSearchFilter.empty()))
                .isInstanceOf(ScraperException.class);
    }

    @Test
    @DisplayName("resposta vazia não gera vagas")
    void emptyResults() {
        when(fetcher.get(eq("remotive"), anyString())).thenReturn("{\"jobs\":[]}");

        assertThat(scraper.search(JobSearchFilter.empty())).isEmpty();
    }
}
