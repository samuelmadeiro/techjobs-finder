package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.config.CacheConfig;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import com.techjobs.finder.scraper.ScraperOrchestrator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Custo do filtro de país e da quantidade escolhida.
 *
 * <p>A pergunta que este benchmark responde é uma só: escolher um país inédito faz a busca
 * esperar coleta? A coleta simulada demora 1,5 s de propósito — se a requisição dependesse
 * dela, apareceria nos percentis na hora.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "logging.level.com.techjobs.finder.service.CountrySearchBenchmarkTest=INFO")
class CountrySearchBenchmarkTest extends PostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CountrySearchBenchmarkTest.class);

    private static final int WARMUP = 20;
    private static final int SAMPLES = 200;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobIngestionService ingestionService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TechnologySeedService technologySeedService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ScraperOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        jdbc.update("DELETE FROM search_cache_entry");
        jdbc.update("DELETE FROM scraping_job");
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
        technologySeedService.seed();

        // Coleta lenta: é o que provaria, se a busca esperasse por ela.
        doAnswer(invocation -> {
            Thread.sleep(1500);
            return List.of(ScrapeResult.success("remoteok", List.of(), Duration.ZERO));
        }).when(orchestrator).collect(any(), any());

        List<RawJob> jobs = new ArrayList<>();
        String[] locations = {"São Paulo, Brasil", "Austin, United States", "Lisboa, Portugal",
                "Toronto, Canada", "Berlin, Germany", "Anywhere", "Remote", "London"};
        for (int i = 0; i < 1200; i++) {
            jobs.add(new RawJob()
                    .setTitle("Pessoa Desenvolvedora " + (i % 2 == 0 ? "Java" : "Python") + " " + i)
                    .setCompany("Empresa " + (i % 60))
                    .setLocation(locations[i % locations.length])
                    .setDescriptionHtml("<p>Java, Spring Boot, PostgreSQL</p>")
                    .setUrl("https://bench-pais.test/j/" + i)
                    .setWorkModelHint("remote")
                    .setPublishedAt(Instant.now().minusSeconds(i * 30L))
                    .setSourceCode("remoteok"));
        }
        ingestionService.ingest(List.of(ScrapeResult.success("remoteok", jobs, Duration.ZERO)));
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
    }

    private record Percentiles(String label, int samples, double p50, double p95, double p99,
                               double max) {
        @Override
        public String toString() {
            return "%-40s n=%d  p50=%6.2f ms  p95=%6.2f ms  p99=%6.2f ms  máx=%7.2f ms"
                    .formatted(label, samples, p50, p95, p99, max);
        }
    }

    private static Percentiles percentiles(String label, List<Long> nanos) {
        List<Long> sorted = nanos.stream().sorted().toList();
        return new Percentiles(label, sorted.size(), millis(sorted, 0.50), millis(sorted, 0.95),
                millis(sorted, 0.99), sorted.get(sorted.size() - 1) / 1_000_000d);
    }

    private static double millis(List<Long> sorted, double quantile) {
        int index = (int) Math.min(sorted.size() - 1d, Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000d;
    }

    private void search(String query) {
        try {
            mockMvc.perform(get("/api/jobs?" + query)).andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Percentiles measure(String label, String query) {
        for (int i = 0; i < WARMUP; i++) {
            search(query);
        }
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            search(query);
            samples.add(System.nanoTime() - start);
        }
        return percentiles(label, samples);
    }

    @Test
    @DisplayName("país e quantidade: latência por combinação")
    void latencyByCountryAndSize() {
        Percentiles brasil = measure("BR cacheado (size=20)", "country=BR&size=20&sort=date");
        Percentiles eua = measure("US cacheado (size=20)", "country=US&size=20&sort=date");
        Percentiles dez = measure("BR size=10", "country=BR&size=10&sort=date");
        Percentiles cinquenta = measure("BR size=50", "country=BR&size=50&sort=date");
        Percentiles cem = measure("BR size=100", "country=BR&size=100&sort=date");
        // País nunca pesquisado: cada requisição usa um país diferente da lista, então
        // nenhuma delas encontra cache — é o pior caso do filtro novo.
        List<Long> inedito = new ArrayList<>();
        String[] novos = {"CA", "PT", "GB", "DE", "ES", "FR", "AU"};
        for (int i = 0; i < novos.length; i++) {
            long start = System.nanoTime();
            search("country=" + novos[i] + "&size=20&sort=date&keyword=inedito" + i);
            inedito.add(System.nanoTime() - start);
        }

        log.info("\n{}\n{}\n{}\n{}\n{}\n{}", brasil, eua, dez, cinquenta, cem,
                percentiles("país inédito (sem cache)", inedito));

        // Nenhuma requisição chegou perto do 1,5 s da coleta simulada.
        assertThat(percentiles("", inedito).max()).isLessThan(1000);
    }

    @Test
    @DisplayName("cinquenta buscas simultâneas de países diferentes")
    void concurrentSearches() throws Exception {
        String[] paises = {"BR", "US", "PT", "CA", "DE"};
        int requests = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Long> samples;

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String pais = paises[i % paises.length];
                futures.add(pool.submit(() -> {
                    start.await();
                    long began = System.nanoTime();
                    search("country=" + pais + "&size=20&sort=date");
                    return System.nanoTime() - began;
                }));
            }
            start.countDown();
            samples = new ArrayList<>();
            for (Future<Long> future : futures) {
                samples.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        log.info("\n{}", percentiles("50 buscas simultâneas (5 países)", samples));

        // Cinco países, no máximo cinco coletas enfileiradas — não cinquenta.
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE status IN ('QUEUED','RUNNING')",
                Integer.class);
        assertThat(active).isLessThanOrEqualTo(paises.length);
        verifyNoInteractions(orchestrator);
    }
}
