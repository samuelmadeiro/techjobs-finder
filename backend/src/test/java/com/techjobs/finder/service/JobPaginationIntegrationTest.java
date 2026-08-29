package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.dto.PageResponse;
import com.techjobs.finder.dto.job.JobSearchRequest;
import com.techjobs.finder.dto.job.JobSummaryResponse;
import com.techjobs.finder.exception.InvalidFilterException;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Paginação e ordenação feitas pelo banco.
 *
 * <p>O contrato da resposta é o mesmo de antes — {@code content}, {@code page}, {@code size},
 * {@code totalElements}, {@code totalPages}, {@code last} —, então o frontend não muda. O que
 * muda é de onde a página vem, e é isso que estes testes fixam: ordem correta, páginas sem
 * buraco nem repetição, e total do conjunto inteiro em vez de uma amostra.
 */
class JobPaginationIntegrationTest extends PostgresIntegrationTest {

    private static final int TOTAL = 25;

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

        List<RawJob> jobs = new ArrayList<>();
        for (int i = 0; i < TOTAL; i++) {
            jobs.add(new RawJob()
                    .setTitle("Desenvolvedor Java " + i)
                    // Empresa em ordem inversa do tempo: se a ordenação por empresa usasse a
                    // ordem de inserção por acidente, o teste não perceberia.
                    .setCompany("Empresa " + (char) ('Z' - i))
                    .setLocation("Remoto")
                    .setDescriptionHtml("<p>Java e Spring Boot</p>")
                    .setUrl("https://x.test/j/" + i)
                    .setWorkModelHint("remote")
                    // Metade sem data de publicação: exercita o COALESCE do índice.
                    .setPublishedAt(i % 2 == 0 ? Instant.now().minusSeconds(i * 60L) : null)
                    .setSourceCode("remoteok"));
        }
        ingestionService.ingest(List.of(ScrapeResult.success("remoteok", jobs, Duration.ZERO)));
    }

    private PageResponse<JobSummaryResponse> search(String sort, int page, int size) {
        JobSearchRequest request = new JobSearchRequest();
        request.setSort(sort);
        request.setPage(page);
        request.setSize(size);
        return searchService.search(request, null);
    }

    @Test
    @DisplayName("primeira página traz o tamanho pedido e o total do conjunto inteiro")
    void firstPage() {
        PageResponse<JobSummaryResponse> page = search("date", 0, 10);

        assertThat(page.content()).hasSize(10);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(TOTAL);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.last()).isFalse();
    }

    @Test
    @DisplayName("página intermediária e última fecham a contagem, sem repetir nem pular")
    void middleAndLastPages() {
        List<Long> vistos = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            PageResponse<JobSummaryResponse> resposta = search("date", page, 10);
            resposta.content().forEach(job -> vistos.add(job.id()));
            assertThat(resposta.last()).isEqualTo(page == 2);
        }

        assertThat(vistos).hasSize(TOTAL);
        // Nenhum id aparece duas vezes: a ordenação tem desempate determinístico.
        assertThat(vistos).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("página além do fim é vazia, não erro")
    void pageBeyondTheEnd() {
        PageResponse<JobSummaryResponse> page = search("date", 99, 10);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(TOTAL);
        assertThat(page.last()).isTrue();
    }

    @Test
    @DisplayName("sort=date ordena pela data efetiva: publicação quando existe, primeira vista quando não")
    void sortsByEffectiveDate() {
        PageResponse<JobSummaryResponse> page = search("date", 0, TOTAL);

        assertThat(page.content()).hasSize(TOTAL);

        // Quem não informa data de publicação usa a data em que foi visto pela primeira vez —
        // que, para este lote recém-ingerido, é agora. Logo essas vagas vêm antes das que
        // trazem data de publicação no passado. É a semântica do COALESCE, e ela precisa
        // estar visível no teste para não ser confundida com bug.
        List<Instant> publicadas = page.content().stream()
                .map(JobSummaryResponse::publishedAt)
                .filter(java.util.Objects::nonNull)
                .toList();

        int semData = (int) page.content().stream()
                .filter(job -> job.publishedAt() == null)
                .count();
        assertThat(semData).isPositive();

        // As sem data ocupam o começo, em bloco.
        assertThat(page.content().subList(0, semData))
                .allSatisfy(job -> assertThat(job.publishedAt()).isNull());
        // E entre as que têm data, a ordem é da mais recente para a mais antiga.
        assertThat(publicadas).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("sort=company ordena por nome da empresa, sem depender de caixa")
    void sortsByCompanyName() {
        PageResponse<JobSummaryResponse> page = search("company", 0, TOTAL);

        List<String> empresas = page.content().stream()
                .map(job -> job.company() == null ? "" : job.company().name().toLowerCase())
                .toList();
        assertThat(empresas).isSorted();
    }

    @Test
    @DisplayName("tamanho de página acima do máximo é recusado na validação")
    void rejectsOversizedPage() {
        JobSearchRequest request = new JobSearchRequest();
        request.setSize(1_000_000);

        // O teto vem de @Max no DTO: o serviço nunca chega a receber o valor.
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("critério de ordenação desconhecido é recusado, nunca repassado ao SQL")
    void rejectsUnknownSort() {
        JobSearchRequest request = new JobSearchRequest();
        request.setSort("; DROP TABLE job --");

        assertThatThrownBy(request::sortMode).isInstanceOf(InvalidFilterException.class);
    }

    @Test
    @DisplayName("relevância continua vindo do caminho pontuado, com o mesmo contrato")
    void relevanceStillWorks() {
        JobSearchRequest request = new JobSearchRequest();
        request.setSort("relevance");
        request.setSize(5);

        PageResponse<JobSummaryResponse> page = searchService.search(request, null);

        assertThat(page.content()).hasSize(5);
        assertThat(page.totalElements()).isEqualTo(TOTAL);
        assertThat(page.content().get(0).relevance()).isNotNull();
    }
}
