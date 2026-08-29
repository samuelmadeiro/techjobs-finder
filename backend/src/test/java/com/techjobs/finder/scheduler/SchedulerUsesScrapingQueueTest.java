package com.techjobs.finder.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.entity.ScrapingJobStatus;
import com.techjobs.finder.repository.ScrapingJobRepository;
import com.techjobs.finder.scraper.ScraperOrchestrator;
import com.techjobs.finder.service.SearchRefreshService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Os dois produtores de trabalho — scheduler e busca — passaram a usar a mesma fila.
 *
 * <p>Antes existiam três caminhos de execução: o executor do {@code SearchRefreshService}, a
 * chamada inline do {@code refresh()} e a thread virtual do {@code bootstrap()}. Cada um com
 * seu tratamento de erro, nenhum com retry, e todos invisíveis fora do log. O que este teste
 * fixa é que nenhum deles voltou: quem chama scheduler ou busca não aciona o orquestrador —
 * cria uma linha em {@code scraping_job}.
 */
@TestPropertySource(properties = {
        // O scheduler está desligado no perfil de teste para o relógio não interferir; aqui
        // o bean precisa existir, e quem dispara os métodos é a chamada explícita.
        "techjobs.scheduler.enabled=true",
        "techjobs.scraper.enabled=true",
        "techjobs.scraper.harvest.enabled=true",
        // A varredura inicial só acontece com a base abaixo do limiar. O que se mede aqui é
        // para onde ela vai — a fila —, não a decisão de disparar, então o limiar fica alto
        // e o teste deixa de depender de quantas vagas outros testes deixaram no banco.
        "techjobs.scraper.harvest.bootstrap-threshold=1000000"
})
class SchedulerUsesScrapingQueueTest extends PostgresIntegrationTest {

    @Autowired
    private JobRefreshScheduler scheduler;

    @Autowired
    private SearchRefreshService refreshService;

    @Autowired
    private ScrapingJobRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ScraperOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM scraping_job");
        jdbc.update("DELETE FROM search_cache_entry");
    }

    @Test
    @DisplayName("refresh programado enfileira job SEARCH em vez de coletar na própria thread")
    void scheduledRefreshEnqueues() {
        scheduler.refresh();

        verifyNoInteractions(orchestrator);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE mode = 'SEARCH' AND status = 'QUEUED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("varredura profunda enfileira job HARVEST")
    void harvestEnqueues() {
        scheduler.harvest();

        verifyNoInteractions(orchestrator);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE mode = 'HARVEST' AND status = 'QUEUED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("varredura inicial da subida também vai para a fila, sem thread própria")
    void bootstrapEnqueues() {
        scheduler.bootstrap();

        verifyNoInteractions(orchestrator);
        assertThat(repository.countByStatus(ScrapingJobStatus.QUEUED)).isEqualTo(1);
    }

    @Test
    @DisplayName("duas rodadas do scheduler não empilham execuções do mesmo trabalho")
    void repeatedSchedulerRunsDoNotStack() {
        scheduler.refresh();
        scheduler.refresh();
        scheduler.harvest();
        scheduler.harvest();

        // Um SEARCH e um HARVEST: são trabalhos distintos, cada um com uma execução ativa.
        assertThat(repository.countByStatus(ScrapingJobStatus.QUEUED)).isEqualTo(2);
    }

    @Test
    @DisplayName("SearchRefreshService enfileira job em vez de abrir thread de coleta")
    void searchRefreshEnqueues() {
        JobSearchFilter filter = new JobSearchFilter(List.of(), List.of(), null, null, null, null,
                "kotlin", List.of());

        boolean scheduled = refreshService.requestRefresh(filter, false);

        assertThat(scheduled).isTrue();
        verifyNoInteractions(orchestrator);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE fingerprint = ? AND status = 'QUEUED'",
                Integer.class, filter.fingerprint())).isEqualTo(1);
    }

    @Test
    @DisplayName("scheduler e busca compartilham a fila: o mesmo trabalho não vira dois jobs")
    void schedulerAndSearchShareTheQueue() {
        // O refresh programado é a coleta do feed geral: filtro vazio, modo SEARCH.
        scheduler.refresh();
        refreshService.requestRefresh(JobSearchFilter.empty(), true);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE status IN ('QUEUED','RUNNING')",
                Integer.class)).isEqualTo(1);
    }
}
