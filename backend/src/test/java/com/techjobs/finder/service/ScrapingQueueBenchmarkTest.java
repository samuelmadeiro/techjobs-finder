package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.config.CacheConfig;
import com.techjobs.finder.entity.ScrapingJobStatus;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.repository.ScrapingJobRepository;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import com.techjobs.finder.scraper.ScraperOrchestrator;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Mede o que esta fase pode ter estragado: a latência da busca enquanto há coleta
 * acontecendo.
 *
 * <p>Não é um teste com limite fixo de milissegundos — máquina de desenvolvimento e CI
 * oscilam. O que ele afirma é a propriedade: a busca com a fila parada e a busca com o worker
 * ocupado ficam na mesma ordem de grandeza, porque uma nunca espera a outra. Os percentis vão
 * para o log.
 *
 * <p>A coleta é simulada com atraso proposital no orquestrador: rede real deixaria o número
 * medido ser o do site, não o do sistema.
 */
@AutoConfigureMockMvc
// O perfil de teste roda em WARN para o log não poluir a suíte; aqui os números medidos são
// o resultado, então esta classe volta a INFO.
@org.springframework.test.context.TestPropertySource(properties =
        "logging.level.com.techjobs.finder.service.ScrapingQueueBenchmarkTest=INFO")
class ScrapingQueueBenchmarkTest extends PostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ScrapingQueueBenchmarkTest.class);

    private static final int WARMUP = 20;
    private static final int SAMPLES = 200;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobIngestionService ingestionService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ScrapingJobRepository scrapingJobRepository;

    @Autowired
    private ScrapingJobService jobService;

    @Autowired
    private ScrapingJobWorker worker;

    @Autowired
    private TechnologySeedService technologySeedService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ScraperOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM scraping_job");
        jdbc.update("DELETE FROM search_cache_entry");
        jobRepository.deleteAll();
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
        technologySeedService.seed();

        List<RawJob> seed = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            seed.add(new RawJob()
                    .setTitle("Pessoa Desenvolvedora " + (i % 2 == 0 ? "Java" : "Python") + " " + i)
                    .setCompany("Empresa " + (i % 40))
                    .setLocation(i % 3 == 0 ? "Remoto" : "São Paulo")
                    .setDescriptionHtml("<p>Java, Spring Boot, PostgreSQL</p>")
                    .setUrl("https://bench.test/j/" + i)
                    .setWorkModelHint("remote")
                    .setPublishedAt(Instant.now().minusSeconds(i * 30L))
                    .setSourceCode("remoteok"));
        }
        ingestionService.ingest(List.of(ScrapeResult.success("remoteok", seed, Duration.ZERO)));
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
    }

    // ------------------------------------------------------------------ estatística

    private record Percentiles(String label, int samples, double p50, double p95, double p99,
                               double max) {
        @Override
        public String toString() {
            return "%-42s n=%d  p50=%6.2f ms  p95=%6.2f ms  p99=%6.2f ms  máx=%7.2f ms"
                    .formatted(label, samples, p50, p95, p99, max);
        }
    }

    private static Percentiles percentiles(String label, List<Long> nanos) {
        List<Long> sorted = nanos.stream().sorted().toList();
        return new Percentiles(label, sorted.size(),
                millis(sorted, 0.50), millis(sorted, 0.95), millis(sorted, 0.99),
                sorted.get(sorted.size() - 1) / 1_000_000d);
    }

    private static double millis(List<Long> sorted, double quantile) {
        int index = (int) Math.min(sorted.size() - 1d, Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000d;
    }

    private long timed(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    private void search(String query) {
        try {
            mockMvc.perform(get("/api/jobs?" + query)).andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Cookie session() throws Exception {
        return mockMvc.perform(post("/api/auth/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getCookie("tjf_session");
    }

    // ------------------------------------------------------------------ medições

    @Test
    @DisplayName("a busca mantém a latência enquanto o worker coleta")
    void searchLatencyIsUnaffectedByScraping() throws Exception {
        // Coleta lenta de propósito: enquanto ela roda, as buscas continuam sendo medidas.
        AtomicBoolean collecting = new AtomicBoolean();
        doAnswer(invocation -> {
            collecting.set(true);
            Thread.sleep(1500);
            return List.of(ScrapeResult.success("remoteok",
                    List.of(new RawJob().setTitle("Vaga de coleta").setCompany("Empresa X")
                            .setUrl("https://bench.test/live/" + UUID.randomUUID())
                            .setDescriptionHtml("<p>Java</p>").setLocation("Remoto")
                            .setPublishedAt(Instant.now()).setSourceCode("remoteok")),
                    Duration.ZERO));
        }).when(orchestrator).collect(any(), any());

        for (int i = 0; i < WARMUP; i++) {
            search("keyword=java&size=20");
        }

        // (1) fila parada
        List<Long> idle = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            idle.add(timed(() -> search("keyword=java&size=20")));
        }

        // (2) mesma busca, com o worker ocupado com coletas de 1,5 s cada
        for (int i = 0; i < 6; i++) {
            jobService.enqueue(new com.techjobs.finder.dto.job.JobSearchFilter(
                    List.of(), List.of(), null, null, null, null, "carga-" + i, List.of()),
                    com.techjobs.finder.entity.ScrapingJob.Mode.SEARCH, Duration.ofSeconds(5), null);
        }
        assertThat(worker.drain()).isPositive();

        List<Long> underLoad = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            underLoad.add(timed(() -> search("keyword=java&size=20")));
        }
        assertThat(collecting.get()).isTrue();

        Percentiles quiet = percentiles("GET /api/jobs (fila parada)", idle);
        Percentiles busy = percentiles("GET /api/jobs (worker coletando)", underLoad);
        log.info("\n{}\n{}", quiet, busy);

        worker.awaitIdle(Duration.ofSeconds(60));

        // A busca não espera coleta: com o worker ocupado a mediana não pode explodir.
        assertThat(busy.p50()).isLessThan(Math.max(50, quiet.p50() * 5));
    }

    @Test
    @DisplayName("filtro novo responde sem esperar coleta, e POST /api/scraping devolve 202 na hora")
    void newFilterAndEnqueueAreFast() throws Exception {
        doAnswer(invocation -> {
            Thread.sleep(1500);
            return List.of(ScrapeResult.success("remoteok", List.of(), Duration.ZERO));
        }).when(orchestrator).collect(any(), any());

        Cookie session = session();
        for (int i = 0; i < WARMUP; i++) {
            search("keyword=aquecimento" + i + "&size=20");
        }

        // Filtro inédito: é o caso que antes da fase 5 esperava a coleta inteira.
        List<Long> coldFilter = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            int n = i;
            coldFilter.add(timed(() -> search("keyword=inedito" + n + "&size=20")));
        }

        List<Long> enqueue = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            int n = i;
            enqueue.add(timed(() -> {
                try {
                    mockMvc.perform(post("/api/scraping").cookie(session)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"keyword\":\"pedido" + n + "\"}"))
                            .andExpect(status().isAccepted());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }

        log.info("\n{}\n{}",
                percentiles("GET /api/jobs (filtro novo)", coldFilter),
                percentiles("POST /api/scraping (202)", enqueue));

        // Cada filtro novo virou trabalho enfileirado, e nenhuma requisição coletou.
        assertThat(scrapingJobRepository.countByStatus(ScrapingJobStatus.QUEUED)).isPositive();
    }

    @Test
    @DisplayName("latência de fila: quanto tempo um job espera entre QUEUED e RUNNING")
    void queueLatency() throws Exception {
        doAnswer(invocation -> List.of(ScrapeResult.success("remoteok", List.of(), Duration.ZERO)))
                .when(orchestrator).collect(any(), any());

        int jobs = 50;
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            var enqueued = jobService.enqueue(new com.techjobs.finder.dto.job.JobSearchFilter(
                    List.of(), List.of(), null, null, null, null, "fila-" + i, List.of()),
                    com.techjobs.finder.entity.ScrapingJob.Mode.SEARCH, Duration.ofSeconds(5), null);
            long start = System.nanoTime();
            worker.runNext();
            waits.add(System.nanoTime() - start);
            assertThat(jobService.find(enqueued.job().getId()).orElseThrow().getStatus())
                    .isEqualTo(ScrapingJobStatus.COMPLETED);
        }

        // Mede claim + execução + conclusão com a coleta instantânea: é o custo que a
        // infraestrutura de fila acrescenta, sem o tempo das fontes.
        log.info("\n{}", percentiles("QUEUED → COMPLETED (coleta instantânea)", waits));
        assertThat(scrapingJobRepository.countByStatus(ScrapingJobStatus.COMPLETED)).isEqualTo(jobs);
    }

    @Test
    @DisplayName("cem pedidos do mesmo filtro: uma execução, nenhuma duplicada")
    void duplicateExecutionsAreZero() throws Exception {
        java.util.concurrent.atomic.AtomicInteger executions =
                new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            executions.incrementAndGet();
            return List.of(ScrapeResult.success("remoteok", List.of(), Duration.ZERO));
        }).when(orchestrator).collect(any(), any());

        Cookie session = session();
        int requests = 100;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<?> futures = java.util.stream.IntStream.range(0, requests)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        mockMvc.perform(post("/api/scraping").cookie(session)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"keyword\":\"popular\"}"))
                                .andExpect(status().isAccepted());
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (Object future : futures) {
                ((java.util.concurrent.Future<?>) future).get(60, TimeUnit.SECONDS);
            }
        }

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE status IN ('QUEUED','RUNNING')",
                Integer.class);
        assertThat(active).isEqualTo(1);

        // E executar a fila inteira dispara uma coleta só, não cem.
        while (worker.runNext()) {
            // esvazia
        }
        log.info("{} pedidos do mesmo filtro → {} job(s) ativo(s) → {} execução(ões) de scraping",
                requests, active, executions.get());
        assertThat(executions.get()).isEqualTo(1);
    }
}
