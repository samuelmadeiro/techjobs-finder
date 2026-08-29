package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.config.ScrapingWorkerProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.entity.ScrapingJob;
import com.techjobs.finder.entity.ScrapingJobStatus;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.repository.ScrapingJobRepository;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import com.techjobs.finder.scraper.ScraperOrchestrator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * O contrato da fila de scraping, contra o PostgreSQL real.
 *
 * <p>Nada aqui é simulado do lado do banco: claim, lease, índice único e backoff são
 * propriedades de transação e de índice, e um mock de repositório provaria apenas que o
 * mock concorda com o teste. O que está mockado é a fonte externa — o
 * {@link ScraperOrchestrator} —, porque o teste precisa decidir se a coleta dá certo, falha
 * ou demora, e não pode depender de sites de terceiros.
 */
class ScrapingJobWorkerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ScrapingJobService jobService;

    @Autowired
    private ScrapingJobWorker worker;

    @Autowired
    private ScrapingJobRepository repository;

    @Autowired
    private ScrapingWorkerProperties properties;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ScraperOrchestrator orchestrator;

    private static final Duration BUDGET = Duration.ofSeconds(5);

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM scraping_job");
        worker.resetObservedConcurrency();
        when(orchestrator.collect(any(), any())).thenReturn(List.of(
                ScrapeResult.success("remoteok", List.of(rawJob("worker-ok")), Duration.ZERO)));
    }

    private static RawJob rawJob(String slug) {
        return new RawJob()
                .setTitle("Pessoa Desenvolvedora Java " + slug)
                .setCompany("Empresa " + slug)
                .setLocation("Remoto")
                .setDescriptionHtml("<p>Java e Spring Boot</p>")
                .setUrl("https://x.test/" + slug)
                .setWorkModelHint("remote")
                .setPublishedAt(Instant.now())
                .setSourceCode("remoteok");
    }

    private static JobSearchFilter filter(String keyword) {
        return new JobSearchFilter(List.of(), List.of(), null, null, null, null, keyword, List.of());
    }

    private ScrapingJob reload(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------ criação e claim

    @Test
    @DisplayName("job nasce QUEUED, sem worker e sem lease")
    void newJobStartsQueued() {
        ScrapingJob job = jobService.enqueue(filter("queued"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job();

        assertThat(job.getStatus()).isEqualTo(ScrapingJobStatus.QUEUED);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getWorkerId()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
    }

    @Test
    @DisplayName("worker reivindica: RUNNING, com dono, início e prazo")
    void workerClaimsJob() {
        UUID id = jobService.enqueue(filter("claim"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();

        ScrapingJob claimed = jobService.claimNext("worker-a").orElseThrow();

        assertThat(claimed.getId()).isEqualTo(id);
        assertThat(claimed.getStatus()).isEqualTo(ScrapingJobStatus.RUNNING);
        assertThat(claimed.getWorkerId()).isEqualTo("worker-a");
        assertThat(claimed.getStartedAt()).isNotNull();
        assertThat(claimed.getLeaseUntil()).isAfter(Instant.now());
        // A tentativa é contada quando começa, não quando falha.
        assertThat(claimed.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("dois workers não reivindicam o mesmo job")
    void twoWorkersCannotClaimTheSameJob() throws Exception {
        UUID id = jobService.enqueue(filter("disputa"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();

        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
            List<Future<Optional<ScrapingJob>>> futures = java.util.stream.IntStream
                    .range(0, contenders)
                    .mapToObj(i -> pool.<Optional<ScrapingJob>>submit(() -> {
                        start.await();
                        return jobService.claimNext("worker-" + i);
                    }))
                    .toList();
            start.countDown();

            long winners = futures.stream().map(future -> {
                try {
                    return future.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).filter(Optional::isPresent).count();

            // Oito threads, uma linha: o Postgres arbitra e sobra um vencedor.
            assertThat(winners).isEqualTo(1);
        }
        assertThat(reload(id).getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("jobs diferentes são executados independentemente, um por worker")
    void differentJobsRunIndependently() throws Exception {
        UUID first = jobService.enqueue(filter("um"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();
        UUID second = jobService.enqueue(filter("dois"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Optional<ScrapingJob>> a = pool.submit(() -> {
                start.await();
                return jobService.claimNext("worker-a");
            });
            Future<Optional<ScrapingJob>> b = pool.submit(() -> {
                start.await();
                return jobService.claimNext("worker-b");
            });
            start.countDown();

            // SKIP LOCKED: quem chega depois não espera, pega a próxima linha.
            List<UUID> claimed = List.of(a.get(30, TimeUnit.SECONDS).orElseThrow().getId(),
                    b.get(30, TimeUnit.SECONDS).orElseThrow().getId());
            assertThat(claimed).containsExactlyInAnyOrder(first, second);
        }
    }

    // ------------------------------------------------------------------ idempotência

    @Test
    @DisplayName("mesmo fingerprint não gera dois jobs ativos")
    void sameFingerprintHasSingleActiveJob() {
        JobSearchFilter filter = filter("idempotente");

        var first = jobService.enqueue(filter, ScrapingJob.Mode.SEARCH, BUDGET, null);
        var second = jobService.enqueue(filter, ScrapingJob.Mode.SEARCH, BUDGET, null);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.job().getId()).isEqualTo(first.job().getId());
        assertThat(activeCount(filter)).isEqualTo(1);
    }

    @Test
    @DisplayName("cem pedidos simultâneos do mesmo filtro produzem uma execução só")
    void hundredRequestsProduceOneJob() throws Exception {
        JobSearchFilter filter = filter("debandada");
        int requests = 100;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<UUID>> futures = java.util.stream.IntStream.range(0, requests)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return jobService.enqueue(filter, ScrapingJob.Mode.SEARCH, BUDGET, null)
                                .job().getId();
                    }))
                    .toList();
            start.countDown();

            List<UUID> ids = futures.stream().map(future -> {
                try {
                    return future.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).distinct().toList();

            // Todo mundo recebeu o mesmo job: cem pedidos, um scraping.
            assertThat(ids).hasSize(1);
        }
        assertThat(activeCount(filter)).isEqualTo(1);
    }

    @Test
    @DisplayName("terminado sai do índice: o mesmo fingerprint pode ser coletado de novo")
    void terminalJobAllowsNewExecutionLater() {
        JobSearchFilter filter = filter("de-novo");
        UUID first = jobService.enqueue(filter, ScrapingJob.Mode.SEARCH, BUDGET, null).job().getId();
        worker.runNext();
        assertThat(reload(first).getStatus()).isEqualTo(ScrapingJobStatus.COMPLETED);

        var again = jobService.enqueue(filter, ScrapingJob.Mode.SEARCH, BUDGET, null);

        assertThat(again.created()).isTrue();
        assertThat(again.job().getId()).isNotEqualTo(first);
    }

    private Integer activeCount(JobSearchFilter filter) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE fingerprint = ? "
                        + "AND status IN ('QUEUED', 'RUNNING')",
                Integer.class, filter.fingerprint());
    }

    // ------------------------------------------------------------------ execução

    @Test
    @DisplayName("sucesso: job vira COMPLETED e as vagas coletadas chegam ao acervo")
    void successfulExecutionCompletesAndIngests() {
        long before = jobRepository.count();
        // Vaga inédita: a ingestão deduplica por fingerprint, então repetir a mesma vaga de
        // outro teste atualizaria a linha em vez de criar uma.
        when(orchestrator.collect(any(), any())).thenReturn(List.of(ScrapeResult.success(
                "remoteok", List.of(rawJob(UUID.randomUUID().toString())), Duration.ZERO)));
        UUID id = jobService.enqueue(filter("sucesso"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();

        assertThat(worker.runNext()).isTrue();

        ScrapingJob done = reload(id);
        assertThat(done.getStatus()).isEqualTo(ScrapingJobStatus.COMPLETED);
        assertThat(done.getCompletedAt()).isNotNull();
        assertThat(done.getWorkerId()).isNull();
        assertThat(done.getLeaseUntil()).isNull();
        assertThat(jobRepository.count()).isGreaterThan(before);
    }

    @Test
    @DisplayName("execução marca a combinação como coletada, e a busca seguinte não enfileira nada")
    void executionMarksSearchCache() {
        JobSearchFilter filter = filter("marca-cache");
        jobService.enqueue(filter, ScrapingJob.Mode.SEARCH, BUDGET, null);

        worker.runNext();

        Integer marks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_cache_entry WHERE fingerprint = ?",
                Integer.class, filter.fingerprint());
        assertThat(marks).isEqualTo(1);
    }

    @Test
    @DisplayName("falha do scraper não destrói o que já estava no acervo")
    void failureKeepsPreviousResults() {
        // Uma execução boa alimenta o acervo.
        jobService.enqueue(filter("preserva"), ScrapingJob.Mode.SEARCH, BUDGET, null);
        worker.runNext();
        long afterSuccess = jobRepository.count();
        assertThat(afterSuccess).isPositive();

        // A seguinte falha em todas as fontes.
        when(orchestrator.collect(any(), any())).thenReturn(List.of(
                ScrapeResult.failure("remoteok", "Falha de rede: connection reset", Duration.ZERO)));
        UUID id = jobService.enqueue(filter("preserva-2"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();
        worker.runNext();

        assertThat(reload(id).getStatus()).isEqualTo(ScrapingJobStatus.QUEUED);
        // O acervo não encolheu: falha de coleta não apaga vaga nenhuma.
        assertThat(jobRepository.count()).isEqualTo(afterSuccess);
    }

    @Test
    @DisplayName("fonte que responde junto com outra que falha: ingere o que veio e conclui")
    void partialFailureStillCompletes() {
        when(orchestrator.collect(any(), any())).thenReturn(List.of(
                ScrapeResult.success("remoteok", List.of(rawJob("parcial")), Duration.ZERO),
                ScrapeResult.failure("himalayas", "HTTP 500 em himalayas.app", Duration.ZERO)));
        UUID id = jobService.enqueue(filter("parcial"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();

        worker.runNext();

        assertThat(reload(id).getStatus()).isEqualTo(ScrapingJobStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ retry e backoff

    @Test
    @DisplayName("falha em todas as fontes agenda nova tentativa com backoff")
    void failureSchedulesRetryWithBackoff() {
        when(orchestrator.collect(any(), any())).thenReturn(List.of(
                ScrapeResult.failure("remoteok", "Falha de rede: timeout", Duration.ZERO)));
        UUID id = jobService.enqueue(filter("retry"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();

        Instant beforeRun = Instant.now();
        worker.runNext();

        ScrapingJob retried = reload(id);
        assertThat(retried.getStatus()).isEqualTo(ScrapingJobStatus.QUEUED);
        assertThat(retried.getAttemptCount()).isEqualTo(1);
        assertThat(retried.getLastError()).contains("timeout");
        // O backoff configurado é respeitado: a próxima tentativa fica no futuro.
        assertThat(retried.getNextAttemptAt())
                .isAfter(beforeRun.plus(properties.getInitialBackoff()).minusSeconds(2));
    }

    @Test
    @DisplayName("job em backoff não é reivindicado antes da hora")
    void backoffDelaysNextClaim() {
        when(orchestrator.collect(any(), any())).thenReturn(List.of(
                ScrapeResult.failure("remoteok", "Falha de rede: timeout", Duration.ZERO)));
        jobService.enqueue(filter("espera"), ScrapingJob.Mode.SEARCH, BUDGET, null);
        worker.runNext();

        // Nada elegível agora: o próximo instante permitido está adiante.
        assertThat(worker.runNext()).isFalse();
    }

    @Test
    @DisplayName("backoff cresce por tentativa e para no teto configurado")
    void backoffGrowsAndIsCapped() {
        Duration first = jobService.backoffFor(1, ScrapingJobService.FailureKind.TRANSIENT);
        Duration second = jobService.backoffFor(2, ScrapingJobService.FailureKind.TRANSIENT);
        Duration far = jobService.backoffFor(15, ScrapingJobService.FailureKind.TRANSIENT);

        assertThat(first).isEqualTo(properties.getInitialBackoff());
        assertThat(second).isEqualTo(properties.getInitialBackoff().multipliedBy(2));
        assertThat(far).isEqualTo(properties.getMaxBackoff());
    }

    @Test
    @DisplayName("429 espera o backoff de fonte pressionada, não o comum")
    void throttledFailureWaitsLonger() {
        assertThat(jobService.classify("HTTP 429 em arbeitnow.com"))
                .isEqualTo(ScrapingJobService.FailureKind.THROTTLED);
        assertThat(jobService.backoffFor(1, ScrapingJobService.FailureKind.THROTTLED))
                .isEqualTo(properties.getThrottledBackoff());
    }

    @Test
    @DisplayName("erro permanente falha na hora, sem gastar tentativas contra quem já disse não")
    void permanentFailureDoesNotRetry() {
        when(orchestrator.collect(any(), any())).thenReturn(List.of(
                ScrapeResult.failure("weworkremotely",
                        "robots.txt proíbe a coleta de /remote-jobs", Duration.ZERO)));
        UUID id = jobService.enqueue(filter("robots"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();

        worker.runNext();

        ScrapingJob failed = reload(id);
        assertThat(failed.getStatus()).isEqualTo(ScrapingJobStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("esgotadas as tentativas, o job vira FAILED")
    void exhaustedAttemptsFailTheJob() {
        when(orchestrator.collect(any(), any())).thenReturn(List.of(
                ScrapeResult.failure("remoteok", "Falha de rede: connection reset", Duration.ZERO)));
        UUID id = jobService.enqueue(filter("limite"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();

        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            // O backoff é real; o teste adianta o relógio da linha em vez de dormir minutos.
            // Uma hora para trás, e não NOW(): a elegibilidade é comparada com o relógio da
            // aplicação, que não é o mesmo do servidor de banco.
            jdbc.update("UPDATE scraping_job SET next_attempt_at = NOW() - INTERVAL '1 hour' "
                    + "WHERE id = ?", id);
            assertThat(worker.runNext()).isTrue();
        }

        ScrapingJob failed = reload(id);
        assertThat(failed.getStatus()).isEqualTo(ScrapingJobStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(properties.getMaxAttempts());
        assertThat(failed.getCompletedAt()).isNotNull();
        // Terminado sai do índice de ativos: o filtro não fica bloqueado para sempre.
        assertThat(activeCount(filter("limite"))).isZero();
    }

    // ------------------------------------------------------------------ lease

    @Test
    @DisplayName("lease vencida devolve o job à fila, sem duplicar linha")
    void expiredLeaseIsRecovered() {
        UUID id = jobService.enqueue(filter("lease"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();
        jobService.claimNext("worker-que-morreu");
        // Simula o processo morto: a lease vence e ninguém conclui.
        jdbc.update("UPDATE scraping_job SET lease_until = NOW() - INTERVAL '1 minute' WHERE id = ?",
                id);

        int recovered = jobService.recoverExpiredLeases();

        assertThat(recovered).isEqualTo(1);
        ScrapingJob back = reload(id);
        assertThat(back.getStatus()).isEqualTo(ScrapingJobStatus.QUEUED);
        assertThat(back.getWorkerId()).isNull();
        assertThat(back.getLeaseUntil()).isNull();
        // A tentativa consumida continua contada, e não nasceu job novo.
        assertThat(back.getAttemptCount()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);

        // E a linha recuperada volta a ser executável.
        assertThat(worker.runNext()).isTrue();
        assertThat(reload(id).getStatus()).isEqualTo(ScrapingJobStatus.COMPLETED);
    }

    @Test
    @DisplayName("lease vencida na última tentativa encerra o job como FAILED")
    void expiredLeaseOnLastAttemptFails() {
        UUID id = jobService.enqueue(filter("lease-final"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();
        jobService.claimNext("worker-que-morreu");
        jdbc.update("UPDATE scraping_job SET attempt_count = max_attempts, "
                + "lease_until = NOW() - INTERVAL '1 minute' WHERE id = ?", id);

        jobService.recoverExpiredLeases();

        assertThat(reload(id).getStatus()).isEqualTo(ScrapingJobStatus.FAILED);
    }

    @Test
    @DisplayName("lease válida não é recuperada: quem está trabalhando continua dono")
    void validLeaseIsNotTouched() {
        UUID id = jobService.enqueue(filter("lease-viva"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job().getId();
        jobService.claimNext("worker-vivo");

        assertThat(jobService.recoverExpiredLeases()).isZero();
        assertThat(reload(id).getStatus()).isEqualTo(ScrapingJobStatus.RUNNING);
    }

    @Test
    @DisplayName("a lease cobre o orçamento da coleta com folga para a ingestão")
    void leaseIsLongerThanTheExecutionBudget() {
        ScrapingJob job = jobService.enqueue(filter("prazo"), ScrapingJob.Mode.SEARCH, BUDGET, null)
                .job();

        assertThat(jobService.leaseFor(job)).isGreaterThan(BUDGET);
        // E o teto de execução é menor que a lease: o worker desiste antes de outro retomar.
        assertThat(jobService.executionTimeoutFor(job)).isLessThan(jobService.leaseFor(job));
    }

    // ------------------------------------------------------------------ concorrência

    @Test
    @DisplayName("o worker respeita o limite configurado de coletas simultâneas")
    void concurrencyStaysWithinTheConfiguredLimit() throws Exception {
        int jobs = properties.getConcurrency() * 3;
        AtomicInteger simultaneous = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        doAnswer(invocation -> {
            peak.accumulateAndGet(simultaneous.incrementAndGet(), Math::max);
            try {
                // Segura todas as execuções ao mesmo tempo: se o limite não valesse, o pico
                // seria o número de jobs, não a concorrência configurada.
                release.await(20, TimeUnit.SECONDS);
                return List.of(ScrapeResult.success("remoteok", List.of(rawJob("conc")),
                        Duration.ZERO));
            } finally {
                simultaneous.decrementAndGet();
            }
        }).when(orchestrator).collect(any(), any());

        for (int i = 0; i < jobs; i++) {
            jobService.enqueue(filter("conc-" + i), ScrapingJob.Mode.SEARCH, BUDGET, null);
        }

        int started = worker.drain();
        assertThat(started).isEqualTo(properties.getConcurrency());

        // Enquanto as vagas estão ocupadas, um novo ciclo não inicia mais nada.
        assertThat(worker.drain()).isZero();

        release.countDown();
        assertThat(worker.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(peak.get()).isLessThanOrEqualTo(properties.getConcurrency());
        assertThat(worker.maxObservedConcurrency())
                .isLessThanOrEqualTo(properties.getConcurrency());
        assertThat(repository.countByStatus(ScrapingJobStatus.QUEUED))
                .isEqualTo(jobs - properties.getConcurrency());
    }

    @Test
    @DisplayName("duas instâncias contra o mesmo banco executam cada job uma vez só")
    void twoInstancesDoNotDuplicateExecution() throws Exception {
        int jobs = 6;
        AtomicInteger executions = new AtomicInteger();
        when(orchestrator.collect(any(), any())).thenAnswer(invocation -> {
            executions.incrementAndGet();
            return List.of(ScrapeResult.success("remoteok", List.of(rawJob("dupla")),
                    Duration.ZERO));
        });
        for (int i = 0; i < jobs; i++) {
            jobService.enqueue(filter("instancia-" + i), ScrapingJob.Mode.SEARCH, BUDGET, null);
        }

        // Dois laços de worker com ids diferentes disputando a mesma fila: é o que duas
        // réplicas fazem. Quem arbitra é o banco, não combinação entre processos.
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> loops = List.of(
                    pool.submit(() -> drainLoop(start, "instancia-a")),
                    pool.submit(() -> drainLoop(start, "instancia-b")));
            start.countDown();
            int executed = loops.stream().mapToInt(future -> {
                try {
                    return future.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).sum();
            assertThat(executed).isEqualTo(jobs);
        }

        // Nem uma execução a mais: nenhum job rodou duas vezes.
        assertThat(executions.get()).isEqualTo(jobs);
        assertThat(repository.countByStatus(ScrapingJobStatus.COMPLETED)).isEqualTo(jobs);
    }

    /** Um "processo" completo: reivindica e executa até a fila esvaziar. */
    private int drainLoop(CountDownLatch start, String workerId) throws InterruptedException {
        start.await();
        int executed = 0;
        while (true) {
            Optional<ScrapingJob> claimed = jobService.claimNext(workerId);
            if (claimed.isEmpty()) {
                return executed;
            }
            worker.execute(claimed.get());
            executed++;
        }
    }
}
