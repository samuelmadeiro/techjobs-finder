package com.techjobs.finder.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.config.ScraperProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.exception.ScraperException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScraperOrchestratorTest {

    private final ScraperProperties properties = new ScraperProperties();

    private JobScraper scraper(String code, List<RawJob> jobs, RuntimeException failure) {
        return new JobScraper() {
            @Override
            public String getSource() {
                return code;
            }

            @Override
            public String getDisplayName() {
                return code;
            }

            @Override
            public String getBaseUrl() {
                return "https://" + code + ".example";
            }

            @Override
            public List<RawJob> search(JobSearchFilter filter) {
                if (failure != null) {
                    throw failure;
                }
                return jobs;
            }
        };
    }

    private RawJob job(String title) {
        return new RawJob().setTitle(title).setUrl("https://exemplo.com/" + title);
    }

    @Test
    @DisplayName("falha de uma fonte não derruba as demais")
    void isolatesFailures() {
        var orchestrator = new ScraperOrchestrator(List.of(
                scraper("a", List.of(job("vaga-a")), null),
                scraper("b", List.of(job("vaga-b")), null),
                scraper("c", null, new ScraperException("c", "site fora do ar"))),
                properties);

        List<ScrapeResult> results = orchestrator.collect(JobSearchFilter.empty(), Duration.ofSeconds(10));

        assertThat(results).hasSize(3);
        assertThat(results.stream().filter(ScrapeResult::success).mapToInt(r -> r.jobs().size()).sum()).isEqualTo(2);
        assertThat(results.stream().filter(r -> !r.success()))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.source()).isEqualTo("c");
                    assertThat(result.errorMessage()).contains("site fora do ar");
                });
    }

    @Test
    @DisplayName("o orquestrador carimba a fonte em cada vaga coletada")
    void stampsSourceCode() {
        var orchestrator = new ScraperOrchestrator(
                List.of(scraper("a", List.of(job("vaga-a")), null)), properties);

        List<ScrapeResult> results = orchestrator.collect(JobSearchFilter.empty(), Duration.ofSeconds(10));

        assertThat(results.get(0).jobs().get(0).getSourceCode()).isEqualTo("a");
    }

    @Test
    @DisplayName("vaga sem título ou URL é descartada antes da ingestão")
    void dropsUnusableJobs() {
        var orchestrator = new ScraperOrchestrator(
                List.of(scraper("a", List.of(new RawJob().setTitle("sem url")), null)), properties);

        List<ScrapeResult> results = orchestrator.collect(JobSearchFilter.empty(), Duration.ofSeconds(10));

        assertThat(results.get(0).jobs()).isEmpty();
    }

    @Test
    @DisplayName("harvest usa a varredura profunda do scraper, não a busca filtrada")
    void harvestUsesDeepSweep() {
        JobScraper deep = new JobScraper() {
            @Override
            public String getSource() {
                return "deep";
            }

            @Override
            public String getDisplayName() {
                return "deep";
            }

            @Override
            public String getBaseUrl() {
                return "https://deep.example";
            }

            @Override
            public List<RawJob> search(JobSearchFilter filter) {
                return List.of(job("da-busca"));
            }

            @Override
            public List<RawJob> harvest() {
                return List.of(job("da-varredura-1"), job("da-varredura-2"));
            }
        };

        var orchestrator = new ScraperOrchestrator(List.of(deep), properties);

        assertThat(orchestrator.harvest(Duration.ofSeconds(10)).get(0).jobs()).hasSize(2);
        assertThat(orchestrator.collect(JobSearchFilter.empty(), Duration.ofSeconds(10)).get(0).jobs())
                .hasSize(1);
    }

    @Test
    @DisplayName("harvest respeita seu próprio teto de resultados por fonte")
    void harvestUsesOwnLimit() {
        properties.getHarvest().setMaxResultsPerSource(1);
        properties.setMaxResultsPerSource(50);
        var orchestrator = new ScraperOrchestrator(
                List.of(scraper("a", List.of(job("v1"), job("v2"), job("v3")), null)), properties);

        assertThat(orchestrator.harvest(Duration.ofSeconds(10)).get(0).jobs()).hasSize(1);
    }

    @Test
    @DisplayName("scraping desligado por configuração não consulta nenhuma fonte")
    void respectsGlobalSwitch() {
        properties.setEnabled(false);
        var orchestrator = new ScraperOrchestrator(
                List.of(scraper("a", List.of(job("vaga-a")), null)), properties);

        assertThat(orchestrator.collect(JobSearchFilter.empty(), Duration.ofSeconds(10))).isEmpty();
    }

    @Test
    @DisplayName("filtro por fonte restringe quem é consultado")
    void filtersBySource() {
        var orchestrator = new ScraperOrchestrator(List.of(
                scraper("a", List.of(job("vaga-a")), null),
                scraper("b", List.of(job("vaga-b")), null)),
                properties);

        JobSearchFilter filter = new JobSearchFilter(List.of(), List.of(), null, null, null, null, null, List.of("b"));

        assertThat(orchestrator.collect(filter, Duration.ofSeconds(10)))
                .singleElement()
                .satisfies(result -> assertThat(result.source()).isEqualTo("b"));
    }
}
