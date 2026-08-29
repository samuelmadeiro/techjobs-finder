package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.config.CacheConfig;
import com.techjobs.finder.dto.PageResponse;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.dto.job.JobSearchRequest;
import com.techjobs.finder.dto.job.JobSummaryResponse;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O contrato do fluxo cache-first: a requisição responde com o que existe e nunca espera
 * coleta.
 *
 * <p>Os scrapers estão desligados no perfil de teste, então o que se verifica aqui é a
 * decisão — quando uma coleta é agendada, quantas vezes, e se a resposta sai sem depender
 * dela. A prova de que a resposta não bloqueia mais está no benchmark da aplicação real.
 */
class SearchCacheFirstIntegrationTest extends PostgresIntegrationTest {

    /** Catálogo real: é ele que valida o país na conversão do pedido em filtro. */
    private static final CountryCatalog COUNTRIES = new CountryCatalog();

    @Autowired
    private JobSearchService searchService;

    @Autowired
    private JobIngestionService ingestionService;

    @Autowired
    private SearchCacheService cacheService;

    @Autowired
    private SearchRefreshService refreshService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TechnologySeedService technologySeedService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        jdbc.update("DELETE FROM search_cache_entry");
        // A fila de coleta agora é estado persistente: um job ainda QUEUED de outro teste
        // faria o pedido seguinte ser (corretamente) absorvido pelo job existente.
        jdbc.update("DELETE FROM scraping_job");
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
        technologySeedService.seed();

        List<RawJob> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            jobs.add(new RawJob()
                    .setTitle("Desenvolvedor Java " + i)
                    .setCompany("Empresa " + i)
                    .setLocation("Remoto")
                    .setDescriptionHtml("<p>Java e Spring Boot</p>")
                    .setUrl("https://x.test/j/" + i)
                    .setWorkModelHint("remote")
                    .setPublishedAt(Instant.now().minusSeconds(i * 60L))
                    .setSourceCode("remoteok"));
        }
        ingestionService.ingest(List.of(ScrapeResult.success("remoteok", jobs, Duration.ZERO)));
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
    }

    private JobSearchRequest request(String keyword) {
        JobSearchRequest request = new JobSearchRequest();
        request.setSort("date");
        request.setSize(5);
        request.setKeyword(keyword);
        return request;
    }

    // ------------------------------------------------------------------ cache de resposta

    @Test
    @DisplayName("miss com dados no banco responde na hora e agenda coleta")
    void missServesDatabaseAndSchedulesRefresh() {
        var before = refreshService.stats();

        PageResponse<JobSummaryResponse> page = searchService.search(request("java"), null);

        // A resposta veio do banco, sem esperar fonte externa nenhuma.
        assertThat(page.content()).isNotEmpty();
        assertThat(refreshService.stats().scheduled()).isEqualTo(before.scheduled() + 1);
    }

    @Test
    @DisplayName("segunda busca idêntica é servida do cache, sem nova coleta")
    void secondIdenticalSearchHitsCache() {
        searchService.search(request("java"), null);
        long afterFirst = refreshService.stats().scheduled();

        PageResponse<JobSummaryResponse> second = searchService.search(request("java"), null);

        assertThat(second.content()).isNotEmpty();
        // Nada agendado de novo: a combinação está fresca.
        assertThat(refreshService.stats().scheduled()).isEqualTo(afterFirst);
        assertThat(cacheManager.getCache(CacheConfig.SEARCH_CACHE)).isNotNull();
    }

    @Test
    @DisplayName("filtros equivalentes caem na mesma entrada de cache")
    void equivalentFiltersShareTheKey() {
        JobSearchRequest primeira = new JobSearchRequest();
        primeira.setLanguage(List.of("java", "python"));
        primeira.setKeyword("Desenvolvedor");

        JobSearchRequest segunda = new JobSearchRequest();
        // Mesma coisa dita de outro jeito: ordem trocada, caixa diferente, espaços em volta.
        segunda.setLanguage(List.of("python", "java"));
        segunda.setKeyword("  desenvolvedor  ");

        assertThat(primeira.toFilter(COUNTRIES).fingerprint())
                .isEqualTo(segunda.toFilter(COUNTRIES).fingerprint());
    }

    @Test
    @DisplayName("filtros diferentes produzem chaves diferentes")
    void differentFiltersDoNotCollide() {
        JobSearchRequest java = new JobSearchRequest();
        java.setLanguage(List.of("java"));
        JobSearchRequest python = new JobSearchRequest();
        python.setLanguage(List.of("python"));

        assertThat(java.toFilter(COUNTRIES).fingerprint())
                .isNotEqualTo(python.toFilter(COUNTRIES).fingerprint());
    }

    @Test
    @DisplayName("refresh=true ignora o cache e garante coleta, sem trocar a fonte da resposta")
    void refreshBypassesCache() {
        searchService.search(request("java"), null);

        JobSearchRequest forced = request("java");
        forced.setRefresh(true);
        PageResponse<JobSummaryResponse> page = searchService.search(forced, null);

        // A resposta continua vindo do banco; o que refresh muda é só a coleta.
        assertThat(page.content()).isNotEmpty();

        // E "coleta garantida" agora significa "existe UM job ativo para este filtro", não
        // "mais um job foi criado". O pedido forçado vence o freio de tempo do cache, mas
        // encontra a execução que a primeira busca já enfileirou e se junta a ela: é o mesmo
        // trabalho, e duplicá-lo bateria duas vezes nas mesmas fontes.
        String fingerprint = request("java").toFilter(COUNTRIES).fingerprint();
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE fingerprint = ? "
                        + "AND status IN ('QUEUED', 'RUNNING')", Integer.class, fingerprint);
        assertThat(active).isEqualTo(1);
    }

    @Test
    @DisplayName("ingestão com vaga nova invalida as páginas já montadas")
    void ingestionInvalidatesSearchCache() {
        searchService.search(request("java"), null);

        ingestionService.ingest(List.of(ScrapeResult.success("remoteok", List.of(
                new RawJob().setTitle("Desenvolvedor Java Novo").setCompany("Empresa Nova")
                        .setLocation("Remoto").setDescriptionHtml("<p>Java</p>")
                        .setUrl("https://x.test/j/novo").setWorkModelHint("remote")
                        .setPublishedAt(Instant.now()).setSourceCode("remoteok")),
                Duration.ZERO)));

        PageResponse<JobSummaryResponse> depois = searchService.search(request("java"), null);

        // A vaga nova aparece: a página anterior não ficou congelada no cache.
        assertThat(depois.content()).anySatisfy(job ->
                assertThat(job.title()).contains("Novo"));
    }

    // ------------------------------------------------------------------ frescor

    @Test
    @DisplayName("classificação por idade: fresco, velho e ausente")
    void classifiesFreshness() {
        JobSearchFilter filter = request("kotlin").toFilter(COUNTRIES);

        assertThat(cacheService.stateOf(filter).freshness())
                .isEqualTo(SearchCacheService.Freshness.MISS);

        cacheService.markCollected(filter, 10);
        assertThat(cacheService.stateOf(filter).freshness())
                .isEqualTo(SearchCacheService.Freshness.FRESH);

        // Envelhece o marcador para além do fresh-ttl, mas dentro do stale-ttl.
        jdbc.update("UPDATE search_cache_entry SET executed_at = NOW() - INTERVAL '20 minutes' "
                + "WHERE fingerprint = ?", filter.fingerprint());
        assertThat(cacheService.stateOf(filter).freshness())
                .isEqualTo(SearchCacheService.Freshness.STALE);

        // Além do stale-ttl volta a contar como nunca coletado.
        jdbc.update("UPDATE search_cache_entry SET executed_at = NOW() - INTERVAL '2 hours' "
                + "WHERE fingerprint = ?", filter.fingerprint());
        assertThat(cacheService.stateOf(filter).freshness())
                .isEqualTo(SearchCacheService.Freshness.MISS);
    }

    @Test
    @DisplayName("resultado velho é entregue na hora e a atualização vai para segundo plano")
    void staleIsServedImmediately() {
        JobSearchRequest request = request("java");
        cacheService.markCollected(request.toFilter(COUNTRIES), 5);
        jdbc.update("UPDATE search_cache_entry SET executed_at = NOW() - INTERVAL '20 minutes'");
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();

        long before = refreshService.stats().scheduled();
        long startedAt = System.nanoTime();
        PageResponse<JobSummaryResponse> page = searchService.search(request, null);
        long millis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(page.content()).isNotEmpty();
        assertThat(refreshService.stats().scheduled()).isEqualTo(before + 1);
        // Teto generoso: o que importa é não haver espera por rede no caminho.
        assertThat(millis).isLessThan(2_000);
    }

    // ------------------------------------------------------------------ debandada

    @Test
    @DisplayName("cem buscas simultâneas do mesmo filtro geram uma única coleta")
    void concurrentSearchesTriggerOneRefresh() throws Exception {
        JobSearchRequest request = request("java");
        long before = refreshService.stats().scheduled();

        AtomicInteger comResultado = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Callable<Void>> chamadas = java.util.stream.IntStream.range(0, 100)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        largada.await();
                        PageResponse<JobSummaryResponse> page =
                                searchService.search(request(request.getKeyword()), null);
                        if (!page.content().isEmpty()) {
                            comResultado.incrementAndGet();
                        }
                        return null;
                    })
                    .toList();
            List<Future<Void>> futures = chamadas.stream().map(pool::submit).toList();
            largada.countDown();
            for (Future<Void> future : futures) {
                future.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }

        // Todas responderam com dados...
        assertThat(comResultado.get()).isEqualTo(100);
        // ...e apenas uma assumiu a coleta. O resto foi barrado pelo claim no PostgreSQL.
        assertThat(refreshService.stats().scheduled() - before).isEqualTo(1);
        assertThat(refreshService.stats().skippedByClaim()).isPositive();
    }

    @Test
    @DisplayName("falha na coleta não apaga o que já estava disponível")
    void failedRefreshKeepsPreviousResults() throws Exception {
        JobSearchRequest request = request("java");
        long antes = searchService.search(request, null).totalElements();

        // Força a combinação a ser recoletada; os scrapers estão desligados, então a coleta
        // termina sem trazer nada — o equivalente a uma fonte fora do ar.
        jdbc.update("UPDATE search_cache_entry SET executed_at = NOW() - INTERVAL '2 hours'");
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
        searchService.search(request, null);
        refreshService.awaitQuiet(Duration.ofSeconds(30));

        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
        assertThat(searchService.search(request, null).totalElements()).isEqualTo(antes);
    }
}
