package com.techjobs.finder.service;

import com.techjobs.finder.config.ScrapingWorkerProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.entity.ScrapingJob;
import com.techjobs.finder.scraper.ScrapeResult;
import com.techjobs.finder.scraper.ScraperOrchestrator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Quem executa a coleta. Um laço que pergunta ao banco, reivindica e trabalha.
 *
 * <p>Substitui os dois mecanismos anteriores — o {@code ThreadPoolExecutor} do
 * {@code SearchRefreshService} e a execução inline no {@code JobRefreshScheduler} —, que
 * tinham em comum guardar o trabalho na memória de um processo. Aqui o processo é
 * descartável: se ele morrer no meio, a lease vence e outro worker retoma a mesma linha.
 *
 * <p>A coleta em si continua sendo do {@link ScraperOrchestrator} e a persistência do
 * {@link JobIngestionService}. Nada de scraping é reimplementado aqui.
 */
@Component
public class ScrapingJobWorker {

    private static final Logger log = LoggerFactory.getLogger(ScrapingJobWorker.class);

    private final ScrapingJobService jobService;
    private final ScraperOrchestrator orchestrator;
    private final JobIngestionService ingestionService;
    private final SearchCacheService cacheService;
    private final ScrapingWorkerProperties properties;

    /**
     * Identifica esta instância no banco. Só diagnóstico: a exclusão mútua vem do índice e do
     * lock, nunca da comparação deste valor.
     */
    private final String workerId;

    private final Semaphore slots;
    private final ExecutorService executor;
    private final ScheduledExecutorService poller;

    /** Execuções em voo, para o teto de tempo ser aplicado por quem está fora delas. */
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxObservedConcurrency = new AtomicInteger();

    private record Running(Future<?> task, Instant deadline) {
    }

    public ScrapingJobWorker(ScrapingJobService jobService,
                             ScraperOrchestrator orchestrator,
                             JobIngestionService ingestionService,
                             SearchCacheService cacheService,
                             ScrapingWorkerProperties properties) {
        this.jobService = jobService;
        this.orchestrator = orchestrator;
        this.ingestionService = ingestionService;
        this.cacheService = cacheService;
        this.properties = properties;
        this.workerId = buildWorkerId();

        int concurrency = Math.max(1, properties.getConcurrency());
        this.slots = new Semaphore(concurrency);
        this.executor = Executors.newFixedThreadPool(concurrency,
                runnable -> Thread.ofPlatform().name("scraping-worker-", 0).unstarted(runnable));
        this.poller = Executors.newSingleThreadScheduledExecutor(
                runnable -> Thread.ofPlatform().name("scraping-poller").daemon(true)
                        .unstarted(runnable));
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "desconhecido";
        }
        return host + "/" + ProcessHandle.current().pid() + "/"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            log.info("Worker de scraping desligado por configuração");
            return;
        }
        poller.scheduleWithFixedDelay(this::tick,
                properties.getInitialDelay().toMillis(),
                properties.getPollInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Worker de scraping ativo: id={} concorrência={} intervalo={}",
                workerId, properties.getConcurrency(), properties.getPollInterval());
    }

    /**
     * Um ciclo: recupera abandonados, encerra o que passou do tempo, e enche as vagas livres.
     *
     * <p>Nunca lança: uma exceção aqui cancelaria o agendamento periódico e o worker morreria
     * em silêncio até o próximo restart.
     */
    void tick() {
        try {
            enforceExecutionTimeouts();
            jobService.recoverExpiredLeases();
            drain();
        } catch (RuntimeException e) {
            log.error("Ciclo do worker de scraping falhou", e);
        }
    }

    /**
     * Reivindica enquanto houver vaga livre e job elegível.
     *
     * @return quantas execuções foram iniciadas neste ciclo
     */
    public int drain() {
        int started = 0;
        while (slots.tryAcquire()) {
            var claimed = jobService.claimNext(workerId);
            if (claimed.isEmpty()) {
                slots.release();
                break;
            }
            ScrapingJob job = claimed.get();
            Instant deadline = Instant.now().plus(jobService.executionTimeoutFor(job));
            try {
                Future<?> task = executor.submit(() -> runGuarded(job, true));
                running.put(job.getId(), new Running(task, deadline));
                started++;
            } catch (RuntimeException e) {
                // Pool recusou (desligamento): devolve o job à fila em vez de perdê-lo.
                slots.release();
                jobService.fail(job, ScrapingJobService.FailureKind.TRANSIENT,
                        "Worker indisponível: " + e.getMessage());
                break;
            }
        }
        return started;
    }

    /**
     * Reivindica e executa uma única vez na thread chamadora.
     *
     * <p>Existe para o teste poder afirmar o que aconteceu sem depender de temporização, e
     * para o benchmark medir a latência de fila. O caminho é exatamente o de produção: mesmo
     * claim, mesma execução, mesma conclusão.
     *
     * @return {@code true} se havia job elegível e ele foi executado
     */
    public boolean runNext() {
        var claimed = jobService.claimNext(workerId);
        if (claimed.isEmpty()) {
            return false;
        }
        runGuarded(claimed.get(), false);
        return true;
    }

    /**
     * @param permitHeld se esta execução consumiu uma vaga de concorrência, que precisa
     *                   voltar ao semáforo ao final. A execução inline de teste e benchmark
     *                   não consome vaga: ela roda na thread de quem chamou.
     */
    private void runGuarded(ScrapingJob job, boolean permitHeld) {
        int current = inFlight.incrementAndGet();
        maxObservedConcurrency.accumulateAndGet(current, Math::max);
        try {
            execute(job);
        } finally {
            inFlight.decrementAndGet();
            running.remove(job.getId());
            if (permitHeld) {
                slots.release();
            }
        }
    }

    /**
     * A execução propriamente dita: coleta com o orquestrador, entrega à ingestão, marca o
     * cache de busca e fecha o job.
     *
     * <p>Falha de coleta nunca apaga acervo: o que já está no banco continua valendo, e as
     * fontes que responderam são ingeridas mesmo quando outra falhou. O job só é considerado
     * malsucedido quando <em>nenhuma</em> fonte respondeu.
     */
    void execute(ScrapingJob job) {
        long startedAt = System.nanoTime();
        JobSearchFilter filter = jobService.deserialize(job);
        Duration budget = Duration.ofSeconds(job.getBudgetSeconds());
        log.info("JOB_STARTED jobId={} fingerprint={} mode={} attempt={}/{} budgetSeconds={}",
                job.getId(), job.getFingerprint(), job.getMode(), job.getAttemptCount(),
                job.getMaxAttempts(), job.getBudgetSeconds());

        try {
            // O orçamento é respeitado dentro do orquestrador, que cancela a fonte lenta e a
            // reporta como falha. Não há timeout novo aqui: seria um segundo relógio para o
            // mesmo prazo.
            List<ScrapeResult> results = job.getMode() == ScrapingJob.Mode.HARVEST
                    ? orchestrator.harvest(budget)
                    : orchestrator.collect(filter, budget);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            if (results.isEmpty()) {
                // Scraping desligado ou nenhuma fonte habilitada para este filtro. Não é
                // falha: repetir daria exatamente o mesmo resultado.
                jobService.complete(job, 0, elapsed);
                return;
            }

            List<ScrapeResult> successes = results.stream().filter(ScrapeResult::success).toList();
            if (successes.isEmpty()) {
                String message = results.stream()
                        .map(result -> result.source() + ": " + result.errorMessage())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("Nenhuma fonte respondeu");
                jobService.fail(job, jobService.classify(message), message);
                return;
            }

            ingestionService.ingest(results);
            int collected = successes.stream().mapToInt(result -> result.jobs().size()).sum();

            // Marca a combinação como coletada agora: é o que faz a próxima busca do mesmo
            // filtro ser servida direto do banco em vez de enfileirar outro job.
            if (job.getMode() == ScrapingJob.Mode.SEARCH) {
                cacheService.markCollected(filter, collected);
            }
            jobService.complete(job, collected, Duration.ofNanos(System.nanoTime() - startedAt));
        } catch (RuntimeException e) {
            // Interrupção (teto de tempo) chega como exceção de runtime na maioria dos
            // caminhos. Limpar o sinal antes de gravar o estado: uma thread interrompida não
            // consegue falar com o banco, e o job ficaria RUNNING até a lease vencer.
            boolean interrupted = Thread.interrupted();
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (interrupted) {
                message = "Execução cancelada por exceder o tempo limite";
            }
            log.error("Execução do job {} falhou: {}", job.getId(), message, e);
            jobService.fail(job, interrupted
                    ? ScrapingJobService.FailureKind.TRANSIENT
                    : jobService.classify(message), message);
        }
    }

    /**
     * Cancela execuções que passaram do teto.
     *
     * <p>Cancelamento é interrupção — {@code Future.cancel(true)} —, nunca
     * {@code Thread.stop}: a tarefa está no meio de I/O de rede e de transação de banco, e
     * matar a thread deixaria conexão e transação penduradas. Se a tarefa ignorar a
     * interrupção, a lease ainda vence depois e outra instância retoma o job.
     */
    void enforceExecutionTimeouts() {
        Instant now = Instant.now();
        running.forEach((jobId, entry) -> {
            if (entry.deadline().isBefore(now) && !entry.task().isDone()) {
                log.warn("JOB_TIMEOUT jobId={} deadline={} — cancelando execução", jobId,
                        entry.deadline());
                entry.task().cancel(true);
            }
        });
    }

    /** Espera as execuções em andamento terminarem. Usado em teste e no desligamento. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (inFlight.get() == 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return inFlight.get() == 0;
    }

    public String workerId() {
        return workerId;
    }

    public int inFlight() {
        return inFlight.get();
    }

    /** Maior número de execuções simultâneas já observado nesta instância. */
    public int maxObservedConcurrency() {
        return maxObservedConcurrency.get();
    }

    public void resetObservedConcurrency() {
        maxObservedConcurrency.set(0);
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
        executor.shutdownNow();
    }
}
