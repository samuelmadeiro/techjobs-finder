package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.dto.job.JobSummaryResponse;
import com.techjobs.finder.dto.job.JobSearchRequest;
import com.techjobs.finder.dto.PageResponse;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Fluxo real contra PostgreSQL: ingestão -> deduplicação -> consulta filtrada -> relevância.
 * Os scrapers ficam desligados; o lote é montado à mão para o teste ser determinístico.
 */
class JobSearchIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JobIngestionService ingestionService;

    @Autowired
    private JobSearchService searchService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TechnologySeedService technologySeedService;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        technologySeedService.seed();
        ingestionService.ingest(List.of(
                ScrapeResult.success("remotive", List.of(
                        raw("Estágio em Desenvolvimento Java", "Empresa XYZ", "João Pessoa - PB",
                                "Java, Spring Boot e PostgreSQL", "https://remotive.com/j/1"),
                        raw("Desenvolvedor Java Júnior", "Empresa XYZ", "Remoto",
                                "Java com Spring Boot", "https://remotive.com/j/2"),
                        raw("Senior Python Engineer", "Outra Empresa", "Remoto",
                                "Python, Django e AWS", "https://remotive.com/j/3")),
                        Duration.ZERO),
                // Mesma vaga da fonte anterior, publicada em outro site.
                ScrapeResult.success("arbeitnow", List.of(
                        raw("desenvolvedor java junior", "Empresa XYZ Ltda", "remoto",
                                "Java com Spring Boot", "https://arbeitnow.com/j/999")),
                        Duration.ZERO)));
    }

    private RawJob raw(String title, String company, String location, String description, String url) {
        return new RawJob()
                .setTitle(title)
                .setCompany(company)
                .setLocation(location)
                .setDescriptionHtml("<p>" + description + "</p>")
                .setUrl(url)
                .setWorkModelHint("remote")
                .setPublishedAt(Instant.now())
                .setSourceCode(url.contains("remotive") ? "remotive" : "arbeitnow");
    }

    @Test
    @DisplayName("vagas repetidas entre fontes são persistidas uma única vez")
    void deduplicatesAcrossSources() {
        assertThat(jobRepository.countByActiveTrue()).isEqualTo(3);
    }

    @Test
    @DisplayName("filtro por linguagem e nível devolve apenas o que combina")
    void filtersByLanguageAndLevel() {
        JobSearchRequest request = new JobSearchRequest();
        request.setLanguage(List.of("java"));
        request.setLevel("INTERNSHIP");

        PageResponse<JobSummaryResponse> page = searchService.search(request, null);

        assertThat(page.content()).isNotEmpty();
        assertThat(page.content().get(0).experienceLevel()).isEqualTo(ExperienceLevel.INTERNSHIP);
        assertThat(page.content().get(0).languages()).contains("Java");
    }

    @Test
    @DisplayName("a vaga mais aderente ao filtro aparece em primeiro")
    void ranksByRelevance() {
        JobSearchRequest request = new JobSearchRequest();
        request.setLanguage(List.of("java"));
        request.setTechnology(List.of("spring-boot"));
        request.setLevel("JUNIOR");
        request.setWorkModel("REMOTE");

        PageResponse<JobSummaryResponse> page = searchService.search(request, null);

        assertThat(page.content()).isNotEmpty();
        JobSummaryResponse first = page.content().get(0);
        assertThat(first.title()).isEqualTo("Desenvolvedor Java Júnior");
        assertThat(first.relevance()).isGreaterThanOrEqualTo(90);
    }

    @Test
    @DisplayName("busca por palavra-chave encontra por título e por resumo")
    void filtersByKeyword() {
        JobSearchRequest byTitle = new JobSearchRequest();
        byTitle.setKeyword("estágio");

        PageResponse<JobSummaryResponse> titleResults = searchService.search(byTitle, null);

        // Acentuação não pode atrapalhar: o título é comparado na forma normalizada.
        assertThat(titleResults.content()).isNotEmpty();
        assertThat(titleResults.content().get(0).title()).contains("Estágio");

        JobSearchRequest byDescription = new JobSearchRequest();
        byDescription.setKeyword("django");

        PageResponse<JobSummaryResponse> descriptionResults =
                searchService.search(byDescription, null);

        assertThat(descriptionResults.content()).hasSize(1);
        assertThat(descriptionResults.content().get(0).title()).isEqualTo("Senior Python Engineer");
    }

    @Test
    @DisplayName("filtro sem correspondência devolve página vazia, não erro")
    void emptyResultIsNotAnError() {
        JobSearchRequest request = new JobSearchRequest();
        request.setLanguage(List.of("cobol"));

        PageResponse<JobSummaryResponse> page = searchService.search(request, null);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    @DisplayName("paginação respeita o tamanho pedido")
    void paginates() {
        JobSearchRequest request = new JobSearchRequest();
        request.setSize(2);

        PageResponse<JobSummaryResponse> page = searchService.search(request, null);

        assertThat(page.content()).hasSize(2);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.last()).isFalse();
    }

    @Test
    @DisplayName("modalidade remota é reconhecida na ingestão")
    void detectsWorkModel() {
        JobSearchRequest request = new JobSearchRequest();
        request.setWorkModel("REMOTE");

        PageResponse<JobSummaryResponse> page = searchService.search(request, null);

        assertThat(page.content()).allMatch(job -> job.workModel() == WorkModel.REMOTE);
    }

    @Test
    @DisplayName("nível e anos de experiência inferidos do texto são persistidos")
    void persistsInferredLevelAndYears() {
        ingestionService.ingest(List.of(ScrapeResult.success("remotive", List.of(
                raw("Engenheiro de Software", "Empresa Nova", "Remoto",
                        "Mínimo de 8 anos de experiência. Você vai liderar o time e definir a arquitetura.",
                        "https://remotive.com/j/500")), Duration.ZERO)));

        var job = jobRepository.findAll().stream()
                .filter(j -> "Engenheiro de Software".equals(j.getTitle()))
                .findFirst()
                .orElseThrow();

        assertThat(job.getExperienceLevel()).isEqualTo(ExperienceLevel.SENIOR);
        assertThat(job.getExperienceYears()).isEqualTo(8);
    }

    @Test
    @DisplayName("vaga sem qualquer sinal de nível fica como UNKNOWN")
    void keepsUnknownWithoutSignals() {
        ingestionService.ingest(List.of(ScrapeResult.success("remotive", List.of(
                raw("Pessoa Desenvolvedora", "Empresa Neutra", "Remoto",
                        "Venha fazer parte de um time incrível em uma empresa em crescimento.",
                        "https://remotive.com/j/501")), Duration.ZERO)));

        var job = jobRepository.findAll().stream()
                .filter(j -> "Pessoa Desenvolvedora".equals(j.getTitle()))
                .findFirst()
                .orElseThrow();

        assertThat(job.getExperienceLevel()).isEqualTo(ExperienceLevel.UNKNOWN);
        assertThat(job.getExperienceYears()).isNull();
    }

    @Test
    @DisplayName("reingerir o mesmo lote não cria vagas duplicadas")
    void reingestionIsIdempotent() {
        long before = jobRepository.countByActiveTrue();
        ingestionService.ingest(List.of(ScrapeResult.success("remotive", List.of(
                raw("Desenvolvedor Java Júnior", "Empresa XYZ", "Remoto",
                        "Java com Spring Boot", "https://remotive.com/j/2")), Duration.ZERO)));

        assertThat(jobRepository.countByActiveTrue()).isEqualTo(before);
    }
}
